package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.repository.CompetitionReportOutboxRepository;
import com.stock_spoon.river_be.competition.service.CompetitionCompletionService;
import com.stock_spoon.river_be.competition.service.CompetitionTradingHours;
import com.stock_spoon.river_be.user.entity.User;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
class CompetitionReportOutboxCompletionTests {
    @Autowired CompetitionCompletionService completion;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean CompetitionTradingHours tradingHours;
    @MockitoSpyBean CompetitionReportOutboxRepository outboxes;

    @Test
    void completionCommitsOnePendingRequestAndRepeatedCompletionKeepsItsId() {
        long id = fixture();
        afterClose();
        completion.completeCompetition(id);
        String requestId = jdbc.queryForObject("select request_id from competition_report_outbox where competition_id=?", String.class, id);
        completion.completeCompetition(id);
        assertThat(jdbc.queryForObject("select count(*) from competition_report_outbox where competition_id=?", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from competition_report_outbox where competition_id=?", String.class, id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select request_id from competition_report_outbox where competition_id=?", String.class, id)).isEqualTo(requestId);
        assertThat(state(id)).isEqualTo("COMPLETED");
    }

    @Test
    void outboxFailureRollsBackCompletionAndFinalPerformances() {
        long id = fixture();
        afterClose();
        doThrow(new IllegalStateException("outbox 저장 실패" )).when(outboxes).saveAndFlush(any(CompetitionReportOutbox.class));
        assertThatThrownBy(() -> completion.completeCompetition(id)).isInstanceOf(IllegalStateException.class);
        assertThat(state(id)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("select count(*) from competition_participants where competition_id=? and final_asset_value is not null", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from competition_daily_performances where participant_id in (select participant_id from competition_participants where competition_id=?)", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from competition_report_outbox where competition_id=?", Long.class, id)).isZero();
    }

    @Test
    void aiCompetitionCreatesOneRequestPerAiAndDoesNotDuplicateOnRepeat() {
        long id = aiFixture();
        afterClose();
        completion.completeCompetition(id);
        assertThat(jdbc.queryForObject("select count(*) from competition_report_outbox where competition_id=?", Long.class, id)).isEqualTo(4);
        var requestIds = jdbc.queryForList("select request_id from competition_report_outbox where competition_id=? order by outbox_id", String.class, id);
        assertThat(requestIds).doesNotHaveDuplicates();
        assertThat(jdbc.queryForList("select participant_id from competition_report_outbox where competition_id=? order by participant_id", Long.class, id))
                .isEqualTo(jdbc.queryForList("select participant_id from competition_participants where competition_id=? order by participant_id", Long.class, id));
        completion.completeCompetition(id);
        assertThat(jdbc.queryForList("select request_id from competition_report_outbox where competition_id=? order by outbox_id", String.class, id)).isEqualTo(requestIds);
        assertThat(state(id)).isEqualTo("COMPLETED");
    }

    @Test
    void secondAiOutboxFailureRollsBackAllRequestsAndCompletion() {
        long id = aiFixture();
        afterClose();
        var savedCount = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (savedCount.incrementAndGet() == 2) throw new IllegalStateException("두 번째 AI 저장 실패");
            CompetitionReportOutbox outbox = call.getArgument(0);
            em.persist(outbox);
            em.flush();
            return outbox;
        }).when(outboxes).saveAndFlush(any(CompetitionReportOutbox.class));
        assertThatThrownBy(() -> completion.completeCompetition(id)).isInstanceOf(IllegalStateException.class);
        assertThat(state(id)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("select count(*) from competition_report_outbox where competition_id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from competition_participants where competition_id=? and final_asset_value is not null", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from competition_daily_performances where participant_id in (select participant_id from competition_participants where competition_id=?)", Long.class, id)).isZero();
        verify(outboxes, times(2)).saveAndFlush(any(CompetitionReportOutbox.class));
    }

    private long aiFixture() {
        return new TransactionTemplate(transactions).execute(status -> {
            var user = new User("AI종료" + UUID.randomUUID().toString().substring(0, 5)); em.persist(user);
            var competition = new Competition(user, "AI 리포트", Competition.Type.AI_VS_AI,
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5), 100000, 4); em.persist(competition);
            // AI 자동 시작 정책과 분리하여 이미 진행 중인 대결의 종료 경로를 검증한다.
            em.flush();
            em.createNativeQuery("update competitions set state='IN_PROGRESS' where competition_id=:id")
                    .setParameter("id", competition.getId()).executeUpdate();
            for (int i = 0; i < 4; i++) {
                var account = new Account(user, "AI" + i, 100000, true); em.persist(account);
                var participant = new CompetitionParticipant(competition, account); em.persist(participant);
                em.persist(new CompetitionAgent(participant, i, 1, java.util.List.of(), java.util.List.of(), "전략" + i));
            }
            return competition.getId();
        });
    }

    @Test
    void beforeCloseDoesNotCreateRequest() {
        long id = fixture();
        doReturn(ZonedDateTime.parse("2026-10-05T15:29:59+09:00[Asia/Seoul]")).when(tradingHours).now();
        completion.completeCompetition(id);
        assertThat(state(id)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("select count(*) from competition_report_outbox where competition_id=?", Long.class, id)).isZero();
    }

    private void afterClose() {
        doReturn(ZonedDateTime.parse("2026-10-05T15:30:00+09:00[Asia/Seoul]")).when(tradingHours).now();
    }

    private String state(long id) {
        return jdbc.queryForObject("select state from competitions where competition_id=?", String.class, id);
    }

    private long fixture() {
        return new TransactionTemplate(transactions).execute(status -> {
            var user = new User("종료" + UUID.randomUUID().toString().substring(0, 5));
            em.persist(user);
            var competition = new Competition(user, "전송 대기", Competition.Type.USER_VS_AI,
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5), 100000, 1);
            competition.start(ZonedDateTime.parse("2026-10-01T10:00:00+09:00[Asia/Seoul]").toInstant());
            em.persist(competition);
            var account = new Account(user, "사용자", 100000, false);
            var ai = new Account(user, "AI", 100000, true);
            em.persist(account);
            em.persist(ai);
            em.persist(new CompetitionParticipant(competition, account));
            em.persist(new CompetitionParticipant(competition, ai));
            return competition.getId();
        });
    }
}
