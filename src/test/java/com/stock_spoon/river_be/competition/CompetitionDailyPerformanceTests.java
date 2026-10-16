package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.entity.CompetitionParticipant;
import com.stock_spoon.river_be.competition.repository.CompetitionDailyPerformanceRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionParticipantRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.competition.service.CompetitionDailyPerformanceService;
import com.stock_spoon.river_be.competition.service.CompetitionTradingHours;
import com.stock_spoon.river_be.execution.entity.Execution;
import com.stock_spoon.river_be.execution.repository.ExecutionRepository;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class CompetitionDailyPerformanceTests {
    @Autowired CompetitionDailyPerformanceService service;
    @Autowired CompetitionDailyPerformanceRepository performances;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionParticipantRepository participants;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired ExecutionRepository executions;
    @MockitoBean KiwoomMarketClient market;
    @MockitoSpyBean CompetitionTradingHours hours;
    private Competition competition;
    private CompetitionParticipant userParticipant;
    private Account userAccount;
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    @BeforeEach
    void setup() {
        var user = users.save(new User("일별" + UUID.randomUUID().toString().substring(0, 5)));
        competition = new Competition(user, "일별 성적", Competition.Type.USER_VS_AI,
                MONDAY, MONDAY.plusDays(11), 10_000_000, 1);
        competition.start(time("2026-10-12T09:00:00+09:00").toInstant());
        competition = competitions.save(competition);
        userAccount = accounts.save(new Account(user, "사용자", 10_000_000, false));
        userParticipant = participants.save(new CompetitionParticipant(competition, userAccount));
        var ai = accounts.save(new Account(user, "AI", 10_000_000, true));
        participants.save(new CompetitionParticipant(competition, ai));
    }

    @Test
    void concurrentRunsKeepOneRecordPerParticipantAndDate() throws Exception {
        doReturn(time("2026-10-12T16:00:00+09:00")).when(hours).now();
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Void> record = () -> {
            start.await();
            service.recordMissingDays(competition.getId());
            return null;
        };
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(record);
            var second = executor.submit(record);
            start.countDown();
            first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            second.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(performances.findAllByParticipantCompetitionId(competition.getId())).hasSize(2);
        assertThatThrownBy(() -> performances.saveAndFlush(
                new com.stock_spoon.river_be.competition.entity.CompetitionDailyPerformance(userParticipant, MONDAY, 10_000_000)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void reconstructsPastAssetsWithoutIncludingNextDaysSaleAndDoesNotOverwrite() {
        trade(Order.Side.BUY, 100_000, 10, "2026-10-12T10:00:00+09:00");
        trade(Order.Side.SELL, 130_000, 4, "2026-10-13T10:00:00+09:00");
        // 이 주문은 미체결이므로 평가에 영향을 주지 않는다.
        orders.save(Order.pendingLimit(userAccount, "005930", Order.Side.BUY, 100, 50_000,
                Order.Source.USER, null, time("2026-10-12T11:00:00+09:00").toInstant()));
        when(market.closingPrice("005930", MONDAY)).thenReturn(120_000L);
        when(market.closingPrice("005930", MONDAY.plusDays(1))).thenReturn(110_000L);
        doReturn(time("2026-10-13T16:00:00+09:00")).when(hours).now();
        service.recordMissingDays(competition.getId());
        var rows = performances.findAllByParticipantIdOrderByRecordDateAsc(userParticipant.getId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getAssetValue()).isEqualTo(10_200_000);
        assertThat(rows.get(0).getCumulativeReturnPercent()).isEqualByComparingTo("2.0");
        // 화요일: 현금 952만원 + 남은 6주 × 11만원 = 1,018만원.
        assertThat(rows.get(1).getAssetValue()).isEqualTo(10_180_000);
        assertThat(rows.get(1).getCumulativeReturnPercent()).isEqualByComparingTo("1.8");
        assertThat(performances.findAllByParticipantCompetitionId(competition.getId())).hasSize(4);
        service.recordMissingDays(competition.getId());
        assertThat(performances.findAllByParticipantIdOrderByRecordDateAsc(userParticipant.getId())).hasSize(2);
        verify(market, times(1)).closingPrice("005930", MONDAY);
    }

    @Test
    void recordsNothingBeforeFirstCloseAndSkipsWeekendsDuringCatchUp() {
        doReturn(time("2026-10-12T15:29:59+09:00")).when(hours).now();
        service.recordMissingDays(competition.getId());
        assertThat(performances.findAllByParticipantCompetitionId(competition.getId())).isEmpty();
        doReturn(time("2026-10-18T12:00:00+09:00")).when(hours).now();
        service.recordMissingDays(competition.getId());
        assertThat(performances.findAllByParticipantIdOrderByRecordDateAsc(userParticipant.getId()))
                .extracting(p -> p.getRecordDate()).containsExactly(MONDAY, MONDAY.plusDays(1),
                        MONDAY.plusDays(2), MONDAY.plusDays(3), MONDAY.plusDays(4));
        verifyNoInteractions(market);
    }

    @Test
    void missingClosingPriceRollsBackBothParticipantsAndCanBeRetried() {
        trade(Order.Side.BUY, 100_000, 10, "2026-10-12T10:00:00+09:00");
        doReturn(time("2026-10-12T16:00:00+09:00")).when(hours).now();
        when(market.closingPrice("005930", MONDAY)).thenThrow(new IllegalStateException("종가 미제공"));
        assertThatThrownBy(() -> service.recordMissingDays(competition.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(performances.findAllByParticipantCompetitionId(competition.getId())).isEmpty();
        doReturn(120_000L).when(market).closingPrice("005930", MONDAY);
        service.recordMissingDays(competition.getId());
        assertThat(performances.findAllByParticipantCompetitionId(competition.getId())).hasSize(2);
    }

    private void trade(Order.Side side, long price, long quantity, String instant) {
        var at = time(instant).toInstant();
        var order = Order.pendingLimit(userAccount, "005930", side, quantity, price, Order.Source.USER, null, at);
        order.execute();
        order = orders.save(order);
        executions.save(new Execution(order, price, quantity, null, null, at));
    }

    private static ZonedDateTime time(String value) {
        return ZonedDateTime.parse(value).withZoneSameInstant(java.time.ZoneId.of("Asia/Seoul"));
    }
}
