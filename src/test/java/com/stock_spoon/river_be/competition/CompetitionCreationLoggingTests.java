package com.stock_spoon.river_be.competition;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.competition.entity.CompetitionParticipant;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.competition.service.CompetitionTradingHours;
import com.stock_spoon.river_be.config.RequestLoggingFilter;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
class CompetitionCreationLoggingTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired CompetitionRepository competitions;
    @Autowired JwtTokenProvider tokens;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @MockitoSpyBean CompetitionParticipantRepository participants;
    @MockitoSpyBean CompetitionTradingHours hours;
    private MockMvc mvc;
    private User user;
    private Logger logger;
    private ListAppender<ILoggingEvent> logs;
    private static final String LOGGER = "com.stock_spoon.river_be.competition.logging.CompetitionCreationLog";
    private static final String PATH = "/api/v2/users/me/competitions";

    @BeforeEach
    void setup() {
        mvc = webAppContextSetup(context).addFilters(new RequestLoggingFilter()).apply(springSecurity()).build();
        user = new User("생성로그");
        user.completeOnboarding();
        user = users.save(user);
        doReturn(ZonedDateTime.parse("2026-10-20T21:00:00+09:00")).when(hours).now();
        logger = (Logger) LoggerFactory.getLogger(LOGGER);
        logs = new ListAppender<>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(logs);
        logs.stop();
        MDC.clear();
    }

    @Test
    void logsBusinessRuleWithStageCodeAndRequestIdWithoutRequestValues() throws Exception {
        mvc.perform(post(PATH).cookie(auth()).with(csrf()).contentType("application/json")
                .content(body().replace("user_vs_ai", "private-invalid-type")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_COMPETITION_TYPE"));
        var event = failure();
        assertThat(event.getFormattedMessage()).contains("stage=validate_competition_type", "code=INVALID_COMPETITION_TYPE",
                "userId=" + user.getId(), "status=400");
        assertThat(event.getMDCPropertyMap().get("requestId")).isNotBlank();
        assertThat(event.getFormattedMessage()).doesNotContain("private-invalid-type", "private-name", tokens.issue(user.getId()).accessToken());
        assertThat(event.getLevel().toString()).isEqualTo("WARN");
    }

    @Test
    void logsFieldConstraintsWithoutRejectedValues() throws Exception {
        mvc.perform(post(PATH).cookie(auth()).with(csrf()).contentType("application/json")
                .content(body().replace("10000000", "-1")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(failure().getFormattedMessage()).contains("stage=request_validation", "initialCapital:Min")
                .doesNotContain("private-name", "rejectedValue", "=-1");
    }

    @Test
    void logsMalformedJsonWithoutBodyOrParserMessage() throws Exception {
        mvc.perform(post(PATH).cookie(auth()).with(csrf()).contentType("application/json")
                .content("{\"competition_name\":\"private-parser-secret\",\"start_date\":\"invalid-date-secret\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(failure().getFormattedMessage()).contains("stage=request_deserialization", "code=INVALID_REQUEST", "rootCauseType=")
                .doesNotContain("private-parser-secret", "invalid-date-secret");
    }

    @Test
    void logsSaveFailureWithInternalIdsAndSqlMetadataAndPreservesRollback() {
        long count = competitions.count();
        doThrow(new DataIntegrityViolationException("private-sql-secret",
                new SQLException("private-db-secret", "23000", 1062)))
                .when(participants).save(any(CompetitionParticipant.class));
        assertThatThrownBy(() -> mvc.perform(post(PATH).cookie(auth()).with(csrf()).contentType("application/json").content(body())))
                .hasRootCauseInstanceOf(SQLException.class);
        assertThat(failure().getFormattedMessage()).contains("stage=user_participant_save", "code=DATABASE_ERROR",
                "competitionId=", "accountId=", "rootCauseType=SQLException", "sqlState=23000", "sqlErrorCode=1062")
                .doesNotContain("private-sql-secret", "private-db-secret", "private-name");
        assertThat(failure().getLevel().toString()).isEqualTo("ERROR");
        assertThat(competitions.count()).isEqualTo(count);
    }

    @Test
    void logsTransactionCommitFailureAndCleansMdcBeforeNextRequest() throws Exception {
        long count = competitions.count();
        var saved = new AtomicInteger();
        doAnswer(call -> {
            CompetitionParticipant result = call.getArgument(0);
            entityManager.persist(result);
            if (saved.incrementAndGet() == 2) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("private-commit-secret");
                    }
                });
            }
            return result;
        }).when(participants).save(any(CompetitionParticipant.class));
        assertThatThrownBy(() -> mvc.perform(post(PATH).cookie(auth()).with(csrf()).contentType("application/json").content(body())))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(failure().getFormattedMessage()).contains("stage=transaction_commit", "rootCauseType=IllegalStateException")
                .doesNotContain("private-commit-secret");
        assertThat(competitions.count()).isEqualTo(count);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        reset(participants);
        mvc.perform(post(PATH).cookie(auth()).with(csrf()).contentType("application/json").content(body()))
                .andExpect(status().isCreated());
        assertThat(logs.list).hasSize(1);
    }

    private ILoggingEvent failure() {
        assertThat(logs.list).hasSize(1);
        return logs.list.getFirst();
    }
    private Cookie auth() { return new Cookie("access_token", tokens.issue(user.getId()).accessToken()); }
    private String body() {
        return """
                {"competition_name":"private-name","competition_type":"user_vs_ai",
                 "start_date":"2026-10-20","end_date":"2026-10-24",
                 "initial_capital":10000000,"ai_participant_count":1}
                """;
    }
}
