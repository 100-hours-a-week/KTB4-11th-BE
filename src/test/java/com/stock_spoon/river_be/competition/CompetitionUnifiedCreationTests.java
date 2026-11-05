package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.competition.dto.*;
import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.exception.CompetitionException;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.competition.service.*;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import jakarta.persistence.EntityManager;
import java.time.ZonedDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest
class CompetitionUnifiedCreationTests {
    @Autowired CompetitionService service;
    @Autowired CompetitionRepository competitions;
    @MockitoSpyBean CompetitionParticipantRepository participants;
    @Autowired CompetitionAgentRepository agents;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired CompetitionCancellationService cancellation;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager em;
    @MockitoSpyBean CompetitionTradingHours hours;
    User owner;
    final ZonedDateTime now = ZonedDateTime.parse("2026-11-03T10:00:00+09:00[Asia/Seoul]");

    @BeforeEach void setup() {
        doReturn(now).when(hours).now();
        owner = new User("통합 생성"); owner.completeOnboarding(); owner = users.save(owner);
    }

    @ParameterizedTest @ValueSource(ints = {2, 3, 4})
    void createsCompetitionAccountsParticipantsAndStrategiesTogether(int count) {
        var response = service.createCompetition(owner.getId(), request("전략 비교", count));
        assertThat(response.getState()).isEqualTo("ready");
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            var c = competitions.findById(response.getCompetitionId()).orElseThrow();
            assertThat(c.getCompetitionType()).isEqualTo(Competition.Type.AI_VS_AI);
            assertThat(c.getAiParticipantCount()).isEqualTo(count);
            assertThat(c.getStartedAt()).isNull();
            var stored = agents.findAllByParticipantCompetitionIdOrderByPositionAsc(c.getId());
            assertThat(stored).hasSize(count);
            assertThat(participants.findAllByCompetitionIdOrderByIdAsc(c.getId())).hasSize(count);
            for (int i = 0; i < count; i++) {
                var strategy = stored.get(i);
                assertThat(strategy.getPosition()).isEqualTo(i);
                assertThat(strategy.getRiskLevel()).isEqualTo(i + 1);
                assertThat(strategy.getPreferredThemes()).containsExactly("반도체");
                assertThat(strategy.getExcludedThemes()).isEmpty();
                assertThat(strategy.getAdditionalRequest()).isEmpty();
                var account = strategy.getParticipant().getAccount();
                assertThat(account.isAiManaged()).isTrue();
                assertThat(account.getUser().getId()).isEqualTo(owner.getId());
                assertThat(account.getInitialCapital()).isEqualTo(3_000_000);
                assertThat(account.getCashBalance()).isEqualTo(3_000_000);
            }
            assertThatThrownBy(() -> c.start(now.toInstant())).isInstanceOf(IllegalStateException.class);
            new CompetitionStartScheduler(competitions, hours).startWaitingCompetitions();
            assertThat(c.getState()).isEqualTo(Competition.State.READY);
        });
        var accountIds = participants.findAllByCompetitionIdOrderByIdAsc(response.getCompetitionId()).stream()
                .map(p -> p.getAccount().getId()).toList();
        assertThat(accounts.findActiveAiManaged()).extracting(a -> a.getId()).doesNotContainAnyElementsOf(accountIds);
    }

    @Test void userVsAiAllowsNullButRejectsSuppliedAgents() {
        var request = request("기존 대결", 1);
        ReflectionTestUtils.setField(request, "competitionType", "user_vs_ai");
        ReflectionTestUtils.setField(request, "agents", null);
        var response = service.createCompetition(owner.getId(), request);
        assertThat(response.getState()).isEqualTo("in_progress");
        assertThat(participants.findAllByCompetitionIdOrderByIdAsc(response.getCompetitionId()))
                .extracting(p -> p.getAccount().isAiManaged()).containsExactly(false, true);
        assertThat(agents.findAllByParticipantCompetitionIdOrderByPositionAsc(response.getCompetitionId())).isEmpty();
        ReflectionTestUtils.setField(request, "competitionName", "잘못된 설정");
        ReflectionTestUtils.setField(request, "agents", List.of());
        assertCode(() -> service.createCompetition(owner.getId(), request), "INVALID_REQUEST");
    }

    @Test void aiVsAiRejectsMissingEmptyNullItemAndMismatchedAgents() {
        var request = request("누락 설정", 2);
        ReflectionTestUtils.setField(request, "agents", null);
        assertCode(() -> service.createCompetition(owner.getId(), request), "INVALID_REQUEST");
        ReflectionTestUtils.setField(request, "agents", List.of());
        assertCode(() -> service.createCompetition(owner.getId(), request), "INVALID_REQUEST");
        ReflectionTestUtils.setField(request, "agents", Arrays.asList(null, strategy(3)));
        assertCode(() -> service.createCompetition(owner.getId(), request), "INVALID_REQUEST");
        ReflectionTestUtils.setField(request, "agents", List.of(strategy(1), strategy(5), strategy(3)));
        assertCode(() -> service.createCompetition(owner.getId(), request), "AGENT_COUNT_MISMATCH");
    }

    @Test void lastStrategyValidationFailureLeavesNothingSaved() {
        var request = request("테마 오류", 2);
        ReflectionTestUtils.setField(request, "agents", List.of(strategy(1),
                new CompetitionAgentRequest(5, List.of("반도체"), List.of(" 반도체 "), "")));
        long[] before = counts();
        assertCode(() -> service.createCompetition(owner.getId(), request), "INVALID_AGENT_THEMES");
        assertThat(counts()).containsExactly(before);
    }

    @Test void finalParticipantFailureRollsBackTheCompetitionToo() {
        long[] before = counts();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("마지막 저장 실패");
            var participant = call.getArgument(0, CompetitionParticipant.class);
            em.persist(participant); return participant;
        }).when(participants).save(any(CompetitionParticipant.class));
        try {
            assertThatThrownBy(() -> service.createCompetition(owner.getId(), request("전체 롤백", 2)))
                    .isInstanceOf(IllegalStateException.class);
        } finally { reset(participants); }
        assertThat(calls.get()).isEqualTo(2);
        assertThat(counts()).containsExactly(before);
    }

    @Test void concurrentSameNameRequestsCreateOnlyOneCompleteCompetition() throws Exception {
        long[] before = counts();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<String> call = () -> {
                start.await();
                try { service.createCompetition(owner.getId(), request("동시 생성", 2)); return "ok"; }
                catch (CompetitionException e) { return e.getCode(); }
            };
            var first = pool.submit(call); var second = pool.submit(call); start.countDown();
            assertThat(List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS), second.get(15, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ok", "DUPLICATE_ACCOUNT_NAME");
        }
        // AI 대결 계좌는 생성되지만 사용자 계좌 목록에는 노출하지 않는다.
        assertThat(accounts.findAllByUserIdAndActiveTrueAndAiManagedFalseOrderByCreatedAtAscIdAsc(owner.getId())).isEmpty();
        assertThat(counts()).containsExactly(before[0] + 1, before[1] + 2, before[2] + 2, before[3] + 2);
        assertThat(competitions.countByUserIdAndState(owner.getId(), Competition.State.READY)).isEqualTo(1);
    }

    @Test void invalidNestedRiskAndMissingCommonDateFailBeforeSaving() {
        var invalidRisk = request("잘못된 성향", 2);
        ReflectionTestUtils.setField(invalidRisk, "agents", List.of(strategy(0), strategy(5)));
        assertCode(() -> service.createCompetition(owner.getId(), invalidRisk), "INVALID_REQUEST");
        var request = request("잘못된 날짜", 2);
        ReflectionTestUtils.setField(request, "startDate", null);
        var missingDate = request;
        assertCode(() -> service.createCompetition(owner.getId(), missingDate), "INVALID_REQUEST");
    }

    @Test void detailSupportsAiCompetitionButCancellationStillReturnsConflict() {
        var response = service.createCompetition(owner.getId(), request("후속 기능", 2));
        assertThat(service.getCompetitionDetail(owner.getId(), response.getCompetitionId()).getParticipants()).hasSize(2);
        assertThatThrownBy(() -> cancellation.cancel(owner.getId(), response.getCompetitionId()))
                .isInstanceOfSatisfying(CompetitionException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
    }

    CompetitionCreateRequest request(String name, int count) {
        var request = new CompetitionCreateRequest();
        ReflectionTestUtils.setField(request, "competitionName", name);
        ReflectionTestUtils.setField(request, "competitionType", "ai_vs_ai");
        ReflectionTestUtils.setField(request, "startDate", now.toLocalDate());
        ReflectionTestUtils.setField(request, "endDate", now.toLocalDate().plusDays(30));
        ReflectionTestUtils.setField(request, "initialCapital", 3_000_000L);
        ReflectionTestUtils.setField(request, "aiParticipantCount", count);
        ReflectionTestUtils.setField(request, "agents", java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> strategy(i + 1)).toList());
        return request;
    }
    CompetitionAgentRequest strategy(int risk) {
        return new CompetitionAgentRequest(risk, List.of(" 반도체 "), List.of(), null);
    }
    long[] counts() { return new long[]{competitions.count(), accounts.count(), participants.count(), agents.count()}; }
    void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CompetitionException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }
}
