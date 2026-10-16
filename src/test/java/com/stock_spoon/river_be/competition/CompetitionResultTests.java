package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.entity.CompetitionDailyPerformance;
import com.stock_spoon.river_be.competition.entity.CompetitionParticipant;
import com.stock_spoon.river_be.competition.repository.CompetitionDailyPerformanceRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionParticipantRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
class CompetitionResultTests {
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionParticipantRepository participants;
    @Autowired CompetitionDailyPerformanceRepository performances;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired WebApplicationContext context;
    @MockitoBean KiwoomMarketClient market;
    private MockMvc mvc;
    private User user;
    private Competition competition;
    private CompetitionParticipant userParticipant;
    private CompetitionParticipant aiParticipant;
    private static final LocalDate LAST_DAY = LocalDate.of(2026, 10, 16);

    @BeforeEach
    void setup() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        user = users.save(new User("결과" + UUID.randomUUID().toString().substring(0, 5)));
        competition = new Competition(user, "반도체 집중", Competition.Type.USER_VS_AI,
                LAST_DAY.minusDays(4), LAST_DAY, 10_000_000, 1);
        competition.start(ZonedDateTime.parse("2026-10-12T09:00:00+09:00").toInstant());
        competition.complete(Competition.Winner.USER, ZonedDateTime.parse("2026-10-16T16:00:00+09:00").toInstant());
        competition = competitions.save(competition);
        // AI를 먼저 저장해도 참가자 유형으로 사용자·AI 성적을 정확히 구분해야 한다.
        aiParticipant = new CompetitionParticipant(competition, accounts.save(new Account(user, "AI", 10_000_000, true)));
        aiParticipant.recordFinalAssets(10_560_000);
        aiParticipant = participants.save(aiParticipant);
        userParticipant = new CompetitionParticipant(competition, accounts.save(new Account(user, "나", 10_000_000, false)));
        userParticipant.recordFinalAssets(10_820_000);
        userParticipant = participants.save(userParticipant);
    }

    @Test
    void returnsFrozenFinalResultsAndJoinsBothHistoriesByDate() throws Exception {
        point(userParticipant, LAST_DAY, 10_820_000);
        point(aiParticipant, LAST_DAY.minusDays(1), 10_100_000);
        point(userParticipant, LAST_DAY.minusDays(1), 10_200_000);
        point(aiParticipant, LAST_DAY, 10_560_000);
        long count = performances.count();
        mvc.perform(get(path()).cookie(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.competition_id").value(competition.getId().intValue()))
                .andExpect(jsonPath("$.competition_name").value("반도체 집중"))
                .andExpect(jsonPath("$.competition_type").value("user_vs_ai"))
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.start_date").value("2026-10-12"))
                .andExpect(jsonPath("$.end_date").value("2026-10-16"))
                .andExpect(jsonPath("$.d_day").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.winner").value("user"))
                .andExpect(jsonPath("$.user_final_return_percent").value(8.2))
                .andExpect(jsonPath("$.ai_final_return_percent").value(5.6))
                .andExpect(jsonPath("$.participants[0].account_id").value(aiParticipant.getAccount().getId().intValue()))
                .andExpect(jsonPath("$.participants[0].participant_type").value("ai"))
                .andExpect(jsonPath("$.participants[1].account_id").value(userParticipant.getAccount().getId().intValue()))
                .andExpect(jsonPath("$.participants[1].participant_type").value("user"))
                .andExpect(jsonPath("$.daily_performances.length()").value(2))
                .andExpect(jsonPath("$.daily_performances[0].date").value("2026-10-15"))
                .andExpect(jsonPath("$.daily_performances[0].user_cumulative_return_percent").value(2.0))
                .andExpect(jsonPath("$.daily_performances[0].ai_cumulative_return_percent").value(1.0))
                .andExpect(jsonPath("$.daily_performances[1].user_cumulative_return_percent").value(8.2))
                .andExpect(jsonPath("$.daily_performances[1].ai_cumulative_return_percent").value(5.6));
        assertThat(performances.count()).isEqualTo(count);
        verifyNoInteractions(market);
    }

    @Test
    void missingParticipantDayIsNullRatherThanInventedZero() throws Exception {
        point(userParticipant, LAST_DAY.minusDays(1), 10_200_000);
        point(aiParticipant, LAST_DAY, 10_560_000);
        mvc.perform(get(path()).cookie(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily_performances.length()").value(2))
                .andExpect(jsonPath("$.daily_performances[0].ai_cumulative_return_percent").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.daily_performances[1].user_cumulative_return_percent").value(org.hamcrest.Matchers.nullValue()));
    }

    @ParameterizedTest
    @EnumSource(value = Competition.State.class, names = {"READY", "IN_PROGRESS", "CANCELLED"})
    void rejectsResultsBeforeCompletion(Competition.State state) throws Exception {
        ReflectionTestUtils.setField(competition, "state", state);
        competitions.save(competition);
        mvc.perform(get(path()).cookie(auth(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMPETITION_NOT_COMPLETED"));
        verifyNoInteractions(market);
    }

    @Test
    void rejectsOtherOwnersUnknownCompetitionAndUnauthenticatedRequests() throws Exception {
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
        var other = users.save(new User("타인" + UUID.randomUUID().toString().substring(0, 5)));
        mvc.perform(get(path()).cookie(auth(other))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMPETITION_NOT_FOUND"));
        mvc.perform(get("/api/v2/users/me/competitions/" + Long.MAX_VALUE + "/result").cookie(auth(user)))
                .andExpect(status().isNotFound());
        verifyNoInteractions(market);
    }

    @Test
    void absentHistoryRemainsEmptyAndMissingFinalResultIsReported() throws Exception {
        mvc.perform(get(path()).cookie(auth(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.daily_performances").isEmpty())
                .andExpect(jsonPath("$.user_final_return_percent").value(8.2));
        ReflectionTestUtils.setField(competition, "winner", null);
        competitions.save(competition);
        mvc.perform(get(path()).cookie(auth(user))).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMPETITION_RESULT_UNAVAILABLE"));
    }

    private void point(CompetitionParticipant participant, LocalDate date, long assets) {
        performances.save(new CompetitionDailyPerformance(participant, date, assets));
    }
    private String path() { return "/api/v2/users/me/competitions/" + competition.getId() + "/result"; }
    private Cookie auth(User owner) { return new Cookie("access_token", tokens.issue(owner.getId()).accessToken()); }
}
