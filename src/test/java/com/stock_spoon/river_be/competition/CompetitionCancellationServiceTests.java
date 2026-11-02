package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.competition.service.CompetitionCancellationService;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.order.messaging.OrderRequestTransactions;
import com.stock_spoon.river_be.order.service.OrderSubscriptionService;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class CompetitionCancellationServiceTests {
    @Autowired CompetitionCancellationService service;
    @Autowired CompetitionRepository competitions;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.stock_spoon.river_be.competition.service.CompetitionCompletionService completion;
    @Autowired CompetitionParticipantRepository participants;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean OrderRepository orders;
    @MockitoBean OrderRequestTransactions requests;
    @MockitoBean OrderSubscriptionService subscriptions;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired com.stock_spoon.river_be.auth.token.JwtTokenProvider tokens;
    @Autowired com.stock_spoon.river_be.execution.service.OrderExecutionService execution;
    @Autowired com.stock_spoon.river_be.execution.repository.ExecutionRepository executions;
    @Autowired com.stock_spoon.river_be.competition.service.CompetitionService competitionService;

    @Test void normalCompletionHidesBothAccountsAndKeepsFinalResultReadable() {
        var fixture = fixture();
        completion.completeCompetition(fixture.competition().getId());
        assertThat(accounts.findById(fixture.mine().getId()).orElseThrow().isActive()).isFalse();
        assertThat(accounts.findById(fixture.ai().getId()).orElseThrow().isActive()).isFalse();
        assertThat(accounts.findAllByUserIdAndActiveTrueOrderByCreatedAtAscIdAsc(fixture.owner().getId())).isEmpty();
        var result = competitionService.getCompetitionResult(fixture.owner().getId(), fixture.competition().getId());
        assertThat(result.getState()).isEqualTo("completed");
        assertThat(result.getWinner()).isEqualTo("draw");
        assertThat(result.getParticipants()).hasSize(2);
        assertThat(result.getDailyPerformances()).hasSize(1);
        assertThat(result.getUserFinalReturnPercent()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
        assertThat(result.getAiFinalReturnPercent()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void cleanupFailureDoesNotUndoCommittedCancellation(boolean reservationFailure) {
        var fixture = fixture();
        if (reservationFailure) when(requests.cleanupInactiveAccount(fixture.mine().getId()))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        else doThrow(new IllegalStateException("subscription unavailable")).when(subscriptions).refresh();

        assertThat(service.cancel(fixture.owner().getId(), fixture.competition().getId()).getState()).isEqualTo("cancelled");
        assertThat(competitions.findById(fixture.competition().getId()).orElseThrow().getState()).isEqualTo(Competition.State.CANCELLED);
        assertThat(accounts.findById(fixture.mine().getId()).orElseThrow().isActive()).isFalse();
        assertThat(accounts.findById(fixture.ai().getId()).orElseThrow().isActive()).isFalse();
        verify(requests).cleanupInactiveAccount(fixture.ai().getId());
        verify(subscriptions).refresh();
    }

    @Test void executionWaitingForCancellationDoesNotFillTheOrder() throws Exception {
        var fixture = fixture();
        var pending = orders.save(Order.pendingLimit(fixture.mine(), "005930", Order.Side.BUY, 1, 50000,
                Order.Source.USER, null, null, java.util.List.of(), null, null, Instant.now()));
        Object previousClock = org.springframework.test.util.ReflectionTestUtils.getField(execution, "clock");
        org.springframework.test.util.ReflectionTestUtils.setField(execution, "clock",
                Clock.fixed(Instant.parse("2026-10-02T01:00:00Z"), ZoneOffset.UTC));
        var started = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            // 정상 체결이 가능한 장중 시각으로 고정하고, 취소 트랜잭션의 계좌 잠금 뒤로 체결을 대기시킨다.
            assertThat(execution.isTradingTime()).isTrue();
            var future = new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
                service.cancel(fixture.owner().getId(), fixture.competition().getId());
                var fill = executor.submit(() -> {
                    started.countDown();
                    return execution.executeLimit(fixture.mine().getId(), pending.getId(), 49000);
                });
                try { assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                return fill;
            });
            assertThat(future.get(20, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            assertThat(orders.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(Order.Status.CANCELLED);
            assertThat(executions.findForOrder(pending.getId())).isEmpty();
            assertThat(accounts.findById(fixture.mine().getId()).orElseThrow().getCashBalance()).isEqualTo(10000000);
        } finally {
            executor.shutdownNow();
            org.springframework.test.util.ReflectionTestUtils.setField(execution, "clock", previousClock);
        }
    }

    private record Fixture(User owner, Competition competition, Account mine, Account ai) {}

    private Fixture fixture() {
        var owner = users.save(new User("후속 처리 사용자"));
        var competition = new Competition(owner, "후속 처리 대결", Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), 10000000, 1);
        competition.start(Instant.parse("2026-10-01T00:00:00Z"));
        competitions.save(competition);
        var mine = accounts.save(new Account(owner, "후속 사용자 계좌", 10000000, false));
        var ai = accounts.save(new Account(owner, "후속 AI 계좌", 10000000, true));
        participants.save(new CompetitionParticipant(competition, mine));
        participants.save(new CompetitionParticipant(competition, ai));
        return new Fixture(owner, competition, mine, ai);
    }

    @Test void cancellationHttpEndpointValidatesAccessAndReturnsCancelledResponse() throws Exception {
        var owner = users.save(new User("취소 API 사용자"));
        var competition = competitions.save(new Competition(owner, "API 취소", Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), 10000000, 1));
        for (boolean ai : new boolean[] {false, true}) {
            var account = accounts.save(new Account(owner, ai ? "API AI" : "API 사용자", 10000000, ai));
            participants.save(new CompetitionParticipant(competition, account));
        }
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        String path = "/api/v2/users/me/competitions/" + competition.getId() + "/cancel";
        var cookie = new jakarta.servlet.http.Cookie("access_token", tokens.issue(owner.getId()).accessToken());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).cookie(cookie))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        var other = users.save(new User("다른 API 사용자"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .cookie(new jakarta.servlet.http.Cookie("access_token", tokens.issue(other.getId()).accessToken()))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).cookie(cookie)
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.competition_id").value(competition.getId()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.state").value("cancelled"));
        }
        assertThat(competitions.findById(competition.getId()).orElseThrow().getState()).isEqualTo(Competition.State.CANCELLED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cancellationDisablesBothAccountsAndCancelsPendingOrdersWithoutChangingCash(boolean running) {
        var owner = users.save(new User("취소 사용자"));
        var competition = competitions.save(new Competition(owner, "취소", Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), 10000000, 1));
        if (running) {
            competition.start(Instant.parse("2026-10-01T00:00:00Z"));
            competitions.save(competition);
        }
        var userAccount = accounts.save(new Account(owner, "사용자 취소 계좌", 10000000, false));
        var aiAccount = accounts.save(new Account(owner, "AI 취소 계좌", 10000000, true));
        participants.save(new CompetitionParticipant(competition, userAccount));
        participants.save(new CompetitionParticipant(competition, aiAccount));
        var order = orders.save(Order.pendingLimit(userAccount, "005930", Order.Side.BUY, 1, 50000,
                Order.Source.USER, null, null, java.util.List.of(), null, null, Instant.now()));

        service.cancel(owner.getId(), competition.getId());
        service.cancel(owner.getId(), competition.getId());

        assertThat(competitions.findById(competition.getId()).orElseThrow().getState()).isEqualTo(Competition.State.CANCELLED);
        assertThat(accounts.findById(userAccount.getId()).orElseThrow().isActive()).isFalse();
        assertThat(accounts.findById(aiAccount.getId()).orElseThrow().isActive()).isFalse();
        assertThat(accounts.findById(userAccount.getId()).orElseThrow().getCashBalance()).isEqualTo(10000000);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(Order.Status.CANCELLED);
        assertThat(accounts.findAllByUserIdAndActiveTrueOrderByCreatedAtAscIdAsc(owner.getId())).isEmpty();
        verify(requests, atLeastOnce()).cleanupInactiveAccount(userAccount.getId());
    }

    @Test void otherOwnerCannotCancelCompetition() {
        var owner = users.save(new User("소유자"));
        var competition = competitions.save(new Competition(owner, "접근 검사", Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), 10000000, 1));
        assertThatThrownBy(() -> service.cancel(Long.MAX_VALUE, competition.getId()))
                .isInstanceOf(com.stock_spoon.river_be.competition.exception.CompetitionException.class);
        assertThat(competitions.findById(competition.getId()).orElseThrow().getState()).isEqualTo(Competition.State.READY);
    }

    @Test void databaseFailureRollsBackBothAccountsAndCompetitionAndSkipsCleanup() {
        var owner = users.save(new User("롤백 사용자"));
        var competition = competitions.save(new Competition(owner, "롤백 대결", Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), 10000000, 1));
        var mine = accounts.save(new Account(owner, "롤백 사용자 계좌", 10000000, false));
        var ai = accounts.save(new Account(owner, "롤백 AI 계좌", 10000000, true));
        participants.save(new CompetitionParticipant(competition, mine));
        participants.save(new CompetitionParticipant(competition, ai));
        var order = orders.save(Order.pendingLimit(mine, "005930", Order.Side.BUY, 1, 50000,
                Order.Source.USER, null, null, java.util.List.of(), null, null, Instant.now()));
        // 양측 계좌 비활성화 이후 주문 조회가 실패하도록 실제 DB 작업 경계에서 장애를 주입한다.
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("DB unavailable"))
                .when(orders).findAllByAccountIdsAndStatus(anyList(), eq(Order.Status.PENDING));

        assertThatThrownBy(() -> service.cancel(owner.getId(), competition.getId()))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);

        assertThat(accounts.findById(mine.getId()).orElseThrow().isActive()).isTrue();
        assertThat(accounts.findById(ai.getId()).orElseThrow().isActive()).isTrue();
        assertThat(competitions.findById(competition.getId()).orElseThrow().getState()).isEqualTo(Competition.State.READY);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(Order.Status.PENDING);
        verifyNoInteractions(requests, subscriptions);
    }

    @Test void completedCompetitionIsRejectedWith409BeforeChangingAccounts() {
        var owner = users.save(new User("완료 사용자"));
        var competition = new Competition(owner, "완료 대결", Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), 10000000, 1);
        competition.start(Instant.parse("2026-10-01T00:00:00Z"));
        competition.complete(Competition.Winner.USER, competition.tradingEndsAt());
        competitions.save(competition);
        assertThatThrownBy(() -> service.cancel(owner.getId(), competition.getId()))
                .isInstanceOfSatisfying(com.stock_spoon.river_be.competition.exception.CompetitionException.class,
                        error -> {
                            assertThat(error.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                            assertThat(error.getCode()).isEqualTo("COMPETITION_ALREADY_COMPLETED");
                        });
        assertThat(competitions.findById(competition.getId()).orElseThrow().getState()).isEqualTo(Competition.State.COMPLETED);
        verifyNoInteractions(requests, subscriptions);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void cancellationAndCompletionRespectWhoAcquiresCompetitionLockFirst(boolean cancelFirst) throws Exception {
        var owner = users.save(new User("동시 처리 사용자"));
        var competition = new Competition(owner, "동시 처리 대결", Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), 10000000, 1);
        competition.start(Instant.parse("2026-10-01T00:00:00Z"));
        competitions.save(competition);
        for (boolean ai : new boolean[] {false, true}) {
            var account = accounts.save(new Account(owner, ai ? "동시 AI" : "동시 사용자", 10000000, ai));
            participants.save(new CompetitionParticipant(competition, account));
        }
        var firstLocked = new java.util.concurrent.CountDownLatch(1);
        var secondStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseFirst = new java.util.concurrent.CountDownLatch(1);
        Runnable cancel = () -> {
            try { service.cancel(owner.getId(), competition.getId()); }
            catch (com.stock_spoon.river_be.competition.exception.CompetitionException error) {
                if (cancelFirst || error.getStatus() != org.springframework.http.HttpStatus.CONFLICT) throw error;
            }
        };
        Runnable complete = () -> completion.completeCompetition(competition.getId());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            // 첫 작업의 트랜잭션에서 실제 Repository 잠금을 선점한다.
            var first = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .executeWithoutResult(status -> {
                        competitions.findLockedById(competition.getId()).orElseThrow();
                        firstLocked.countDown();
                        try {
                            if (!releaseFirst.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                throw new IllegalStateException("첫 작업 대기 시간 초과");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                        }
                        (cancelFirst ? cancel : complete).run();
                    }));
            assertThat(firstLocked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                (cancelFirst ? complete : cancel).run();
            });
            assertThat(secondStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            releaseFirst.countDown();
            first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            second.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
        var saved = competitions.findById(competition.getId()).orElseThrow();
        assertThat(saved.getState()).isEqualTo(cancelFirst ? Competition.State.CANCELLED : Competition.State.COMPLETED);
        assertThat(saved.getWinner()).isEqualTo(cancelFirst ? null : Competition.Winner.DRAW);
        assertThat(participants.findAllByCompetitionIdOrderByIdAsc(competition.getId()))
                .allSatisfy(link -> {
                    if (cancelFirst) {
                        assertThat(link.getFinalAssetValue()).isNull();
                        assertThat(link.getAccount().isActive()).isFalse();
                    } else assertThat(link.getFinalAssetValue()).isEqualTo(10000000L);
                });
    }
}
