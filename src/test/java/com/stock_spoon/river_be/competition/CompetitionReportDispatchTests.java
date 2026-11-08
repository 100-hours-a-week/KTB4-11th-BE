package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.repository.CompetitionReportOutboxRepository;
import com.stock_spoon.river_be.competition.service.CompetitionReportDispatchScheduler;
import com.stock_spoon.river_be.competition.service.CompetitionReportDispatchService;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.user.entity.User;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"competition.report.queue.enabled=true", "competition.report.dispatch.enabled=false",
        "competition.report.queue.url=https://sqs.ap-northeast-2.amazonaws.com/250832562715/stockspoon-v2-dev-report-request"})
class CompetitionReportDispatchTests {
    private static final String REASON = "550e8400-e29b-41d4-a716-446655440000";
    @Autowired CompetitionReportDispatchService service;
    @MockitoSpyBean CompetitionReportOutboxRepository outboxes;
    @MockitoBean(name = "competitionReportSqsClient") SqsAsyncClient sqs;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void setup() {
        jdbc.update("delete from competition_report_outbox");
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(success());
    }

    @Test void sendsOnlyExecutedAiReasonsWithAiParticipantAndMarksSent() {
        var fixture = fixture(true);
        assertThat(service.dispatch(fixture.outboxId())).isEqualTo(fixture.requestId());
        var captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(captor.capture());
        var body = json.readTree(captor.getValue().messageBody());
        assertThat(body.path("competition_id").asLong()).isEqualTo(fixture.competitionId());
        assertThat(body.path("participant_id").asLong()).isEqualTo(fixture.aiParticipantId());
        assertThat(body.has("user_id")).isFalse();
        assertThat(body.path("portfolio_reason_ids")).isEqualTo(json.readTree("[\"" + REASON + "\"]"));
        assertThat(state(fixture)).isEqualTo("SENT");
        assertThat(service.dispatch(fixture.outboxId())).isNull();
        verify(sqs, times(1)).sendMessage(any(SendMessageRequest.class));
    }

    @Test void failedSendRemainsPendingAndRetryKeepsRequestId() {
        var fixture = fixture(true);
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("network unavailable")))
                .thenReturn(success());
        assertThatThrownBy(() -> service.dispatch(fixture.outboxId())).isInstanceOf(IllegalStateException.class);
        assertThat(state(fixture)).isEqualTo("PENDING");
        service.dispatch(fixture.outboxId());
        assertThat(state(fixture)).isEqualTo("SENT");
        var captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs, times(2)).sendMessage(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(r -> assertThat(r.messageAttributes().get("eventId").stringValue())
                .isEqualTo(fixture.requestId()));
    }

    @Test void statusSaveFailureAfterSendRollsBackToPending() {
        var fixture = fixture(true);
        doThrow(new IllegalStateException("DB flush failed")).when(outboxes).flush();
        assertThatThrownBy(() -> service.dispatch(fixture.outboxId())).isInstanceOf(IllegalStateException.class);
        assertThat(state(fixture)).isEqualTo("PENDING");
        verify(sqs).sendMessage(any(SendMessageRequest.class));
    }

    @Test void parallelDispatchWaitsAndDoesNotSendTwice() throws Exception {
        var fixture = fixture(true);
        var sent = new CountDownLatch(1);
        var response = new CompletableFuture<SendMessageResponse>();
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenAnswer(call -> { sent.countDown(); return response; });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.dispatch(fixture.outboxId()));
            assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.dispatch(fixture.outboxId()));
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            response.complete(SendMessageResponse.builder().messageId("delivered").build());
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(fixture.requestId());
            assertThat(second.get(5, TimeUnit.SECONDS)).isNull();
        } finally { response.complete(SendMessageResponse.builder().messageId("cleanup").build()); }
        verify(sqs, times(1)).sendMessage(any(SendMessageRequest.class));
    }

    @Test void schedulerContinuesPastFailedBatchAndDoesNotRetrySameRowInSameRun() {
        var first = fixture(true);
        var second = fixture(true);
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("first failed")))
                .thenReturn(success());
        new CompetitionReportDispatchScheduler(outboxes, service, 1, true).dispatchPendingReports();
        assertThat(state(first)).isEqualTo("PENDING");
        assertThat(state(second)).isEqualTo("SENT");
        verify(sqs, times(2)).sendMessage(any(SendMessageRequest.class));
    }

    @Test void nonCompletedCompetitionIsNotPublished() {
        var fixture = fixture(false);
        assertThatThrownBy(() -> service.dispatch(fixture.outboxId())).isInstanceOf(IllegalStateException.class);
        assertThat(state(fixture)).isEqualTo("PENDING");
        verify(sqs, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test void missingLegacyReasonIdsAreExcludedWithoutInventingIds() {
        var fixture = fixture(true);
        jdbc.update("update ai_order_reports set reason_id=null where order_id in (select order_id from trade_orders where account_id=?)", fixture.aiAccountId());
        service.dispatch(fixture.outboxId());
        var captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(captor.capture());
        assertThat(json.readTree(captor.getValue().messageBody()).path("portfolio_reason_ids").isEmpty()).isTrue();
        assertThat(state(fixture)).isEqualTo("SENT");
    }

    @Test void allAiRequestsUseTheirOwnReasonsAndFailedAiRetriesIndependently() {
        var fixtures = multiAiFixture();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenAnswer(call ->
                calls.incrementAndGet() == 2
                        ? CompletableFuture.failedFuture(new IllegalStateException("AI2 send failed")) : success());

        new CompetitionReportDispatchScheduler(outboxes, service, 1, true).dispatchPendingReports();
        assertThat(fixtures).extracting(this::state).containsExactly("SENT", "PENDING", "SENT", "SENT");
        var captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs, times(4)).sendMessage(captor.capture());
        for (int i = 0; i < fixtures.size(); i++) {
            var fixture = fixtures.get(i);
            var request = captor.getAllValues().get(i);
            var body = json.readTree(request.messageBody());
            assertThat(body.path("competition_id").asLong()).isEqualTo(fixture.competitionId());
            assertThat(body.path("participant_id").asLong()).isEqualTo(fixture.aiParticipantId());
            assertThat(body.path("portfolio_reason_ids")).isEqualTo(json.readTree("[\"" + reason(i) + "\"]"));
            assertThat(body.has("user_id")).isFalse();
            assertThat(request.messageAttributes().get("eventId").stringValue()).isEqualTo(fixture.requestId());
        }
        new CompetitionReportDispatchScheduler(outboxes, service, 1, true).dispatchPendingReports();
        assertThat(fixtures).extracting(this::state).containsOnly("SENT");
        var retry = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs, times(5)).sendMessage(retry.capture());
        assertThat(retry.getAllValues().getLast().messageAttributes().get("eventId").stringValue())
                .isEqualTo(fixtures.get(1).requestId());
    }

    @Test void outboxPointingToHumanParticipantIsNotPublished() {
        var fixture = fixture(true);
        jdbc.update("update competition_report_outbox set participant_id=(select p.participant_id from competition_participants p join accounts a on a.account_id=p.account_id where p.competition_id=? and a.is_ai_managed=false) where outbox_id=?",
                fixture.competitionId(), fixture.outboxId());
        assertThatThrownBy(() -> service.dispatch(fixture.outboxId())).isInstanceOf(IllegalStateException.class);
        assertThat(state(fixture)).isEqualTo("PENDING");
        verifyNoInteractions(sqs);
    }

    @Test void outboxPointingToAnotherCompetitionAiIsNotPublished() {
        var fixture = fixture(true);
        var other = fixture(true);
        jdbc.update("update competition_report_outbox set participant_id=? where outbox_id=?", other.aiParticipantId(), fixture.outboxId());
        assertThatThrownBy(() -> service.dispatch(fixture.outboxId())).isInstanceOf(IllegalStateException.class);
        assertThat(state(fixture)).isEqualTo("PENDING");
        verifyNoInteractions(sqs);
    }

    private String reason(int index) {
        return "550e8400-e29b-41d4-a716-" + String.format("%012d", index);
    }

    private List<Fixture> multiAiFixture() {
        return new TransactionTemplate(manager).execute(tx -> {
            var user = new User("다중AI" + UUID.randomUUID().toString().substring(0, 5)); em.persist(user);
            var competition = new Competition(user, "AI 리포트", Competition.Type.AI_VS_AI,
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5), 100000, 4); em.persist(competition);
            var fixtures = new ArrayList<Fixture>();
            for (int i = 0; i < 4; i++) {
                var account = new Account(user, "AI" + i, 100000, true); em.persist(account);
                var participant = new CompetitionParticipant(competition, account); em.persist(participant);
                for (int duplicate = 0; duplicate < 2; duplicate++) {
                    var executed = aiOrder(account, reason(i)); executed.execute(); em.persist(executed);
                }
                em.persist(aiOrder(account, UUID.randomUUID().toString()));
                var outbox = new CompetitionReportOutbox(competition, participant, UUID.randomUUID().toString()); em.persist(outbox);
                fixtures.add(new Fixture(outbox.getId(), outbox.getRequestId(), competition.getId(), participant.getId(), user.getId(), account.getId()));
            }
            // 자동 시작 정책 대신 완료 상태의 전송 입력을 직접 준비한다.
            em.flush();
            em.createNativeQuery("update competitions set state='COMPLETED' where competition_id=:id")
                    .setParameter("id", competition.getId()).executeUpdate();
            return fixtures;
        });
    }

    private String state(Fixture fixture) {
        return jdbc.queryForObject("select status from competition_report_outbox where outbox_id=?", String.class, fixture.outboxId());
    }

    private CompletableFuture<SendMessageResponse> success() {
        return CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("message-1").build());
    }

    private Fixture fixture(boolean completed) {
        return new TransactionTemplate(manager).execute(tx -> {
            var user = new User("보고서" + UUID.randomUUID().toString().substring(0, 5)); em.persist(user);
            var competition = new Competition(user, "리포트", Competition.Type.USER_VS_AI,
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5), 100000, 1); em.persist(competition);
            competition.start(Instant.parse("2026-10-01T01:00:00Z"));
            if (completed) competition.complete(Competition.Winner.USER, Instant.parse("2026-10-05T06:30:00Z"));
            var ai = new Account(user, "AI", 100000, true); em.persist(ai);
            var human = new Account(user, "사용자", 100000, false); em.persist(human);
            var outsider = new Account(user, "외부AI", 100000, true); em.persist(outsider);
            var participant = new CompetitionParticipant(competition, ai); em.persist(participant);
            em.persist(new CompetitionParticipant(competition, human));
            for (var status : List.of(Order.Status.EXECUTED, Order.Status.EXECUTED, Order.Status.PENDING, Order.Status.CANCELLED)) {
                var order = aiOrder(ai, status == Order.Status.EXECUTED ? REASON : UUID.randomUUID().toString());
                if (status == Order.Status.EXECUTED) order.execute();
                if (status == Order.Status.CANCELLED) order.cancel(Instant.parse("2026-10-02T01:00:00Z"));
                em.persist(order);
            }
            var legacy = aiOrder(ai, null); legacy.execute(); em.persist(legacy);
            var external = aiOrder(outsider, UUID.randomUUID().toString()); external.execute(); em.persist(external);
            var manual = Order.pendingLimit(human, "005930", Order.Side.BUY, 1, 1000, Order.Source.USER, null, Instant.parse("2026-10-01T01:00:00Z"));
            manual.execute(); em.persist(manual);
            var outbox = new CompetitionReportOutbox(competition, participant, UUID.randomUUID().toString()); em.persist(outbox);
            em.flush();
            return new Fixture(outbox.getId(), outbox.getRequestId(), competition.getId(), participant.getId(), user.getId(), ai.getId());
        });
    }

    private Order aiOrder(Account account, String reasonId) {
        return Order.pendingLimit(account, "005930", Order.Side.BUY, 1, 1000, Order.Source.AI, "판단", "삼성전자",
                List.of(), 20.0, null, reasonId, Instant.parse("2026-10-01T01:00:00Z"));
    }

    private record Fixture(Long outboxId, String requestId, Long competitionId, Long aiParticipantId, Long userId, Long aiAccountId) {}
}
