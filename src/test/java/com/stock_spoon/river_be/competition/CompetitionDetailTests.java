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
import com.stock_spoon.river_be.competition.service.CompetitionTradingHours;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
class CompetitionDetailTests {
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionParticipantRepository participants;
    @Autowired CompetitionDailyPerformanceRepository performances;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired WebApplicationContext context;
    @MockitoBean KiwoomMarketClient market;
    @MockitoSpyBean CompetitionTradingHours hours;
    private MockMvc mvc;
    private Competition competition;
    private CompetitionParticipant userParticipant;
    private CompetitionParticipant aiParticipant;
    private User user;

    @BeforeEach
    void setup() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        doReturn(ZonedDateTime.parse("2026-10-16T13:00:00+09:00[Asia/Seoul]")).when(hours).now();
        user = users.save(new User("상세" + UUID.randomUUID().toString().substring(0, 5)));
        competition = new Competition(user, "반도체 집중", Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 23), 10_000_000, 1);
        competition.start(ZonedDateTime.parse("2026-10-12T09:00:00+09:00").toInstant());
        competition = competitions.save(competition);
        userParticipant = participants.save(new CompetitionParticipant(competition,
                accounts.save(new Account(user, "내 계좌", 10_000_000, false))));
        aiParticipant = participants.save(new CompetitionParticipant(competition,
                accounts.save(new Account(user, "AI 계좌", 10_000_000, true))));
    }

    @Test
    void returnsConfirmedUserHistoryInDateOrderAndAccountIdsWithoutLivePrices() throws Exception {
        performances.save(new CompetitionDailyPerformance(userParticipant, LocalDate.of(2026, 10, 15), 10_200_000));
        performances.save(new CompetitionDailyPerformance(userParticipant, LocalDate.of(2026, 10, 14), 10_120_000));
        performances.save(new CompetitionDailyPerformance(aiParticipant, LocalDate.of(2026, 10, 15), 11_000_000));
        long count = performances.count();
        mvc.perform(get(path()).cookie(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.competition_id").value(competition.getId().intValue()))
                .andExpect(jsonPath("$.competition_name").value("반도체 집중"))
                .andExpect(jsonPath("$.competition_type").value("user_vs_ai"))
                .andExpect(jsonPath("$.state").value("in_progress"))
                .andExpect(jsonPath("$.start_date").value("2026-10-12"))
                .andExpect(jsonPath("$.end_date").value("2026-10-23"))
                .andExpect(jsonPath("$.d_day").value(7))
                .andExpect(jsonPath("$.cumulative_return_percent").value(2.0))
                .andExpect(jsonPath("$.participants.length()").value(2))
                .andExpect(jsonPath("$.participants[0].participant_id").value(userParticipant.getId().intValue()))
                .andExpect(jsonPath("$.participants[0].account_id").value(userParticipant.getAccount().getId().intValue()))
                .andExpect(jsonPath("$.participants[0].participant_type").value("user"))
                .andExpect(jsonPath("$.participants[0].display_name").value("나"))
                .andExpect(jsonPath("$.participants[1].account_id").value(aiParticipant.getAccount().getId().intValue()))
                .andExpect(jsonPath("$.participants[1].participant_type").value("ai"))
                .andExpect(jsonPath("$.participants[1].display_name").value("AI"))
                .andExpect(jsonPath("$.participants[1].cumulative_return_percent").doesNotExist())
                .andExpect(jsonPath("$.daily_performances.length()").value(2))
                .andExpect(jsonPath("$.daily_performances[0].date").value("2026-10-14"))
                .andExpect(jsonPath("$.daily_performances[0].cumulative_return_percent").value(1.2))
                .andExpect(jsonPath("$.daily_performances[1].date").value("2026-10-15"))
                .andExpect(jsonPath("$.daily_performances[1].cumulative_return_percent").value(2.0));
        assertThat(performances.count()).isEqualTo(count);
        verifyNoInteractions(market);
    }

    @Test
    void closingTimeAloneDoesNotAddTodayButSavedCloseIsReturned() throws Exception {
        performances.save(new CompetitionDailyPerformance(userParticipant, LocalDate.of(2026, 10, 15), 10_200_000));
        doReturn(ZonedDateTime.parse("2026-10-16T15:35:00+09:00[Asia/Seoul]")).when(hours).now();
        mvc.perform(get(path()).cookie(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cumulative_return_percent").value(2.0))
                .andExpect(jsonPath("$.daily_performances.length()").value(1));
        performances.save(new CompetitionDailyPerformance(userParticipant, LocalDate.of(2026, 10, 16), 10_290_000));
        doReturn(ZonedDateTime.parse("2026-10-17T12:00:00+09:00[Asia/Seoul]")).when(hours).now();
        mvc.perform(get(path()).cookie(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cumulative_return_percent").value(2.9))
                .andExpect(jsonPath("$.daily_performances.length()").value(2))
                .andExpect(jsonPath("$.daily_performances[1].date").value("2026-10-16"));
        verifyNoInteractions(market);
    }

    @Test
    void firstRecordNotYetAvailableReturnsZeroAndEmptyHistory() throws Exception {
        mvc.perform(get(path()).cookie(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cumulative_return_percent").value(0))
                .andExpect(jsonPath("$.daily_performances").isEmpty());
    }

    @Test
    void readyCompetitionReturnsZeroAndNoDailyPoints() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(competition, "state", Competition.State.READY);
        org.springframework.test.util.ReflectionTestUtils.setField(competition, "startedAt", null);
        competitions.save(competition);
        mvc.perform(get(path()).cookie(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ready"))
                .andExpect(jsonPath("$.cumulative_return_percent").value(0))
                .andExpect(jsonPath("$.daily_performances").isEmpty());
        verifyNoInteractions(market);
    }

    @Test
    void completedCompetitionReturnsFrozenHistoryAndNullDDay() throws Exception {
        doReturn(ZonedDateTime.parse("2026-10-24T12:00:00+09:00[Asia/Seoul]")).when(hours).now();
        competition.complete(Competition.Winner.USER, ZonedDateTime.parse("2026-10-23T16:00:00+09:00").toInstant());
        competitions.save(competition);
        performances.save(new CompetitionDailyPerformance(userParticipant, LocalDate.of(2026, 10, 23), 10_820_000));
        mvc.perform(get(path()).cookie(auth(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.d_day").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.cumulative_return_percent").value(8.2));
        verifyNoInteractions(market);
    }

    @Test
    void rejectsUnauthenticatedOtherOwnersAndUnknownCompetitions() throws Exception {
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
        var other = users.save(new User("타인" + UUID.randomUUID().toString().substring(0, 5)));
        mvc.perform(get(path()).cookie(auth(other))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMPETITION_NOT_FOUND"));
        mvc.perform(get("/api/v2/users/me/competitions/" + Long.MAX_VALUE).cookie(auth(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMPETITION_NOT_FOUND"));
        verifyNoInteractions(market);
    }

    private String path() { return "/api/v2/users/me/competitions/" + competition.getId(); }
    private Cookie auth(User owner) { return new Cookie("access_token", tokens.issue(owner.getId()).accessToken()); }
}
