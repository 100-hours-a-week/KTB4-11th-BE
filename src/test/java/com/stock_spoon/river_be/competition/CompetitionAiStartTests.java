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
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class CompetitionAiStartTests {
    @Autowired CompetitionService service;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionAiSetupOutboxRepository outboxes;
    @Autowired CompetitionParticipantRepository participants;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired WebApplicationContext context;
    @Autowired CompetitionAiSetupCompletionService completion;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @MockitoSpyBean CompetitionTradingHours hours;
    @MockitoBean com.stock_spoon.river_be.auth.service.JwtUserExistenceVerifier userVerifier;
    MockMvc mvc;
    User owner;
    ZonedDateTime now;

    @BeforeEach void setup() {
        now = ZonedDateTime.parse("2026-11-10T08:00:00+09:00[Asia/Seoul]");
        doReturn(now).when(hours).now();
        owner = new User("시작 테스트"); owner.completeOnboarding(); owner = users.save(owner);
        // 인증의 별도 트랜잭션에서는 아직 커밋하지 않은 테스트 회원을 볼 수 없다.
        when(userVerifier.exists(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @ParameterizedTest @ValueSource(ints = {2, 3, 4})
    void allPreparedAccountsBecomeVisibleOnlyAfterTheMarketOpens(int count) throws Exception {
        var c = create(count);
        var event = event(c);
        var ids = participants.findAllByCompetitionIdOrderByIdAsc(c.getId()).stream().map(p -> p.getAccount().getId()).toList();
        tick();
        assertThat(c.getState()).isEqualTo(Competition.State.READY);
        complete(c, event.getEventId(), owner.getId(), "AI", 204);
        tick();
        assertThat(c.getState()).isEqualTo(Competition.State.READY);
        assertThat(accounts.findActiveAiManagedOn(now.toLocalDate())).extracting(a -> a.getId()).doesNotContainAnyElementsOf(ids);
        setTime("2026-11-10T09:00:00+09:00[Asia/Seoul]");
        tick();
        assertThat(c.getState()).isEqualTo(Competition.State.IN_PROGRESS);
        assertThat(c.getStartedAt()).isEqualTo(now.toInstant());
        assertThat(accounts.findActiveAiManagedOn(now.toLocalDate())).extracting(a -> a.getId()).containsAll(ids);
        var started = c.getStartedAt();
        tick();
        complete(c, event.getEventId(), owner.getId(), "AI", 204);
        assertThat(c.getStartedAt()).isEqualTo(started);
    }

    @Test void marketOpeningAloneDoesNotStartAnUnpreparedCompetition() {
        var c = create(2);
        setTime("2026-11-10T10:00:00+09:00[Asia/Seoul]");
        tick();
        assertThat(c.getState()).isEqualTo(Competition.State.READY);
        assertThatThrownBy(() -> c.start(now.toInstant())).isInstanceOf(IllegalStateException.class);
    }

    @Test void completionDuringMarketHoursWaitsForSchedulerAndPreservesFirstReceipt() throws Exception {
        var c = create(2); var event = event(c);
        setTime("2026-11-10T10:00:00+09:00[Asia/Seoul]");
        complete(c, event.getEventId(), owner.getId(), "AI", 204);
        var first = ReflectionTestUtils.getField(c, "aiSetupCompletedAt");
        assertThat(first).isEqualTo(now.toInstant());
        assertThat(c.getState()).isEqualTo(Competition.State.READY);
        // AI 소비 완료가 확인되면 발송 후 DB 저장 실패로 남은 PENDING도 복구됩니다.
        assertThat(event.getStatus()).isEqualTo(CompetitionAiSetupOutbox.Status.SENT);
        setTime("2026-11-10T10:01:00+09:00[Asia/Seoul]");
        complete(c, event.getEventId(), owner.getId(), "AI", 204);
        assertThat(ReflectionTestUtils.getField(c, "aiSetupCompletedAt")).isEqualTo(first);
        tick();
        assertThat(c.getState()).isEqualTo(Competition.State.IN_PROGRESS);
    }

    @Test void rejectsOtherOwnersWrongEventsAndNonAiCallers() throws Exception {
        var c = create(2); var event = event(c);
        var other = users.save(new User("다른 사용자"));
        complete(c, event.getEventId(), other.getId(), "AI", 404);
        complete(c, UUID.randomUUID().toString(), owner.getId(), "AI", 404);
        complete(c, event.getEventId(), owner.getId(), "USER", 403);
        mvc.perform(post(path(c)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"event_id\":\"" + event.getEventId() + "\"}")).andExpect(status().isUnauthorized());
        assertThat(c.getState()).isEqualTo(Competition.State.READY);
    }

    @Test void rejectsBlankEventAndAiServerWideToken() throws Exception {
        var c = create(2);
        complete(c, "", owner.getId(), "AI", 400);
        mvc.perform(post(path(c)).with(csrf()).with(jwt().jwt(j -> j.subject("ai-server").claim("actor", "AI")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"event_id\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void cancelledCompetitionCannotBePreparedOrStarted() throws Exception {
        var c = create(2); var event = event(c);
        c.cancel();
        complete(c, event.getEventId(), owner.getId(), "AI", 409);
        setTime("2026-11-10T10:00:00+09:00[Asia/Seoul]"); tick();
        assertThat(c.getState()).isEqualTo(Competition.State.CANCELLED);
    }

    @Test void weekendEndDateDoesNotAllowStartAfterFridayClose() throws Exception {
        var c = create(2); var event = event(c);
        setTime("2026-11-13T15:30:00+09:00[Asia/Seoul]");
        complete(c, event.getEventId(), owner.getId(), "AI", 409);
        // 일요일 종료라도 실제 거래 종료는 금요일 15:30입니다.
        assertThat(c.getState()).isEqualTo(Competition.State.READY);
    }

    @Test void preparedCompetitionDoesNotStartAfterItsTradingDeadline() throws Exception {
        var c = create(2); complete(c, event(c).getEventId(), owner.getId(), "AI", 204);
        setTime("2026-11-16T09:00:00+09:00[Asia/Seoul]"); tick();
        assertThat(c.getState()).isEqualTo(Competition.State.READY);
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void simultaneousCallbacksAndSchedulersCommitOneStart() throws Exception {
        var c = create(2); var e = event(c);
        setTime("2026-11-10T10:00:00+09:00[Asia/Seoul]");
        var request = new CompetitionAiSetupCompleteRequest(e.getEventId());
        together(() -> completion.complete(owner.getId(), c.getId(), request));
        var prepared = competitions.findById(c.getId()).orElseThrow().getAiSetupCompletedAt();
        assertThat(prepared).isEqualTo(now.toInstant());
        together(this::tick);
        var stored = competitions.findById(c.getId()).orElseThrow();
        assertThat(stored.getState()).isEqualTo(Competition.State.IN_PROGRESS);
        assertThat(stored.getStartedAt()).isEqualTo(now.toInstant());
        setTime("2026-11-10T10:01:00+09:00[Asia/Seoul]");
        completion.complete(owner.getId(), c.getId(), request);
        assertThat(competitions.findById(c.getId()).orElseThrow().getAiSetupCompletedAt()).isEqualTo(prepared);
    }

    @Test void userVsAiCannotReceiveAiSetupCompletion() throws Exception {
        var c = competitions.save(new Competition(owner, "사용자 대결", Competition.Type.USER_VS_AI,
                now.toLocalDate(), now.toLocalDate().plusDays(5), 3_000_000, 1));
        complete(c, UUID.randomUUID().toString(), owner.getId(), "AI", 409);
    }

    void together(Runnable action) throws Exception {
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Void> call = () -> { gate.await(); action.run(); return null; };
            var first = pool.submit(call); var second = pool.submit(call); gate.countDown();
            first.get(15, java.util.concurrent.TimeUnit.SECONDS);
            second.get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
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
    String path(Competition c) { return "/api/v1/competitions/" + c.getId() + "/ai-setup/complete"; }
    void complete(Competition c, String eventId, long userId, String actor, int expected) throws Exception {
        mvc.perform(post(path(c)).with(csrf()).with(jwt().jwt(j -> j.subject(Long.toString(userId)).claim("actor", actor)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"event_id\":\"" + eventId + "\"}"))
                .andExpect(status().is(expected));
    }
}
