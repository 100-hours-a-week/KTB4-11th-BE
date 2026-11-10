package com.stock_spoon.river_be.competition;
import com.stock_spoon.river_be.competition.dto.*;
import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.competition.service.*;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class CompetitionAiStartTests {
    @Autowired CompetitionService service;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionAiSetupOutboxRepository outboxes;
    @Autowired CompetitionParticipantRepository participants;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @MockitoSpyBean CompetitionTradingHours hours;
    User owner;
    ZonedDateTime now;
    @BeforeEach void setup() {
        setTime("2026-11-10T08:00:00+09:00[Asia/Seoul]");
        owner = new User("전송 시작"); owner.completeOnboarding(); owner = users.save(owner);
    }
    @ParameterizedTest @ValueSource(ints = {2, 3, 4})
    void startsSentCompetitionWithoutCallbackWhenMarketOpens(int count) {
        var c = create(count);
        event(c).markSent();
        tick();
        assertThat(c.getState()).isEqualTo(Competition.State.READY);
        var ids = participants.findAllByCompetitionIdOrderByIdAsc(c.getId()).stream().map(p -> p.getAccount().getId()).toList();
        assertThat(accounts.findActiveAiManagedOn(now.toLocalDate())).extracting(a -> a.getId()).doesNotContainAnyElementsOf(ids);
        setTime("2026-11-10T09:00:00+09:00[Asia/Seoul]");
        tick();
        assertThat(c.getState()).isEqualTo(Competition.State.IN_PROGRESS);
        assertThat(c.getStartedAt()).isEqualTo(now.toInstant());
        assertThat(accounts.findActiveAiManagedOn(now.toLocalDate())).extracting(a -> a.getId()).containsAll(ids);
        var first = c.getStartedAt(); tick(); assertThat(c.getStartedAt()).isEqualTo(first);
    }
    @Test void pendingCancelledAndMissingOutboxesNeverStart() {
        var pending = create(2); var cancelled = create(2); var missing = create(2);
        event(cancelled).markCancelled(); outboxes.delete(event(missing)); outboxes.flush();
        setTime("2026-11-10T10:00:00+09:00[Asia/Seoul]"); tick();
        assertThat(List.of(pending, cancelled, missing)).allSatisfy(c -> assertThat(c.getState()).isEqualTo(Competition.State.READY));
    }
    @Test void oldSentCompetitionStartsOnNextMarketDay() {
        var c = create(2); event(c).markSent();
        setTime("2026-11-11T09:00:00+09:00[Asia/Seoul]"); tick();
        assertThat(c.getState()).isEqualTo(Competition.State.IN_PROGRESS);
    }
    @Test void expiredAndCancelledCompetitionsDoNotStart() {
        var expired = create(2); event(expired).markSent();
        var cancelled = create(2); event(cancelled).markSent(); cancelled.cancel();
        setTime("2026-11-16T09:00:00+09:00[Asia/Seoul]"); tick();
        assertThat(expired.getState()).isEqualTo(Competition.State.READY);
        assertThat(cancelled.getState()).isEqualTo(Competition.State.CANCELLED);
    }
    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentSchedulersStartSentCompetitionOnce() throws Exception {
        var c = create(2); var sent = event(c); sent.markSent(); outboxes.saveAndFlush(sent);
        setTime("2026-11-10T10:00:00+09:00[Asia/Seoul]");
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Void> action = () -> { gate.await(); tick(); return null; };
            var first = pool.submit(action); var second = pool.submit(action); gate.countDown();
            first.get(15, java.util.concurrent.TimeUnit.SECONDS);
            second.get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        var stored = competitions.findById(c.getId()).orElseThrow();
        assertThat(stored.getState()).isEqualTo(Competition.State.IN_PROGRESS);
        assertThat(stored.getStartedAt()).isEqualTo(now.toInstant());
    }
    Competition create(int count) {
        var r = new CompetitionCreateRequest();
        ReflectionTestUtils.setField(r, "competitionType", "ai_vs_ai");
        ReflectionTestUtils.setField(r, "competitionName", "시작 " + UUID.randomUUID().toString().substring(0, 8));
        ReflectionTestUtils.setField(r, "startDate", now.toLocalDate());
        ReflectionTestUtils.setField(r, "endDate", now.toLocalDate().plusDays(5));
        ReflectionTestUtils.setField(r, "initialCapital", 3_000_000L);
        ReflectionTestUtils.setField(r, "aiParticipantCount", count);
        var agents = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new CompetitionAgentRequest(i + 1, List.of("반도체"), List.of(), "")).toList();
        ReflectionTestUtils.setField(r, "agents", agents);
        return competitions.findById(service.createCompetition(owner.getId(), r).getCompetitionId()).orElseThrow();
    }
    CompetitionAiSetupOutbox event(Competition c) {
        return outboxes.findAll().stream().filter(o -> o.getCompetition().getId().equals(c.getId())).findFirst().orElseThrow();
    }
    void setTime(String time) { now = ZonedDateTime.parse(time); doReturn(now).when(hours).now(); }
    void tick() {
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(s ->
                new CompetitionStartScheduler(competitions, hours).startWaitingCompetitions());
    }
}
