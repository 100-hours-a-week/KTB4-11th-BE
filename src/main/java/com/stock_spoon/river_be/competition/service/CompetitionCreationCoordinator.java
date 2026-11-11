package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.dto.*;
import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.repository.*;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** 생성 트랜잭션 커밋 후 전송과 시작을 시도한다. 전체를 하나의 트랜잭션으로 묶지 않는다. */
@Service
public class CompetitionCreationCoordinator {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompetitionCreationCoordinator.class);
    private final CompetitionService creation;
    private final CompetitionRepository competitions;
    private final CompetitionAiSetupOutboxRepository outboxes;
    private final ObjectProvider<CompetitionAiSetupDispatchService> dispatch;
    private final CompetitionTradingHours hours;
    private final TransactionTemplate tx;

    public CompetitionCreationCoordinator(CompetitionService creation, CompetitionRepository competitions,
            CompetitionAiSetupOutboxRepository outboxes, ObjectProvider<CompetitionAiSetupDispatchService> dispatch,
            CompetitionTradingHours hours, PlatformTransactionManager manager) {
        this.creation = creation; this.competitions = competitions; this.outboxes = outboxes;
        this.dispatch = dispatch; this.hours = hours;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public CompetitionCreateResponse create(long userId, CompetitionCreateRequest request) {
        var result = creation.createCompetition(userId, request);
        if (!"ai_vs_ai".equals(request.getCompetitionType())) return result;
        // 상위 호출자가 트랜잭션을 열었다면 아직 생성 커밋이 안 됐으므로 복구 스케줄러에 맡긴다.
        if (TransactionSynchronizationManager.isActualTransactionActive() || !hours.isOpen(hours.now())) return result;
        try {
            var sender = dispatch.getIfAvailable();
            if (sender != null) {
                var outbox = outboxes.findByCompetitionId(result.getCompetitionId()).orElseThrow();
                sender.dispatch(outbox.getId()); // 별도 서비스 프록시가 SENT 커밋 후 반환한다.
                tx.executeWithoutResult(status -> {
                    var c = competitions.findLockedById(result.getCompetitionId())
                            .filter(v -> v.getUser().getId().equals(userId)).orElseThrow();
                    if (outboxes.existsByCompetitionIdAndStatus(c.getId(), CompetitionAiSetupOutbox.Status.SENT)) {
                        CompetitionStartScheduler.startIfEligible(c, hours);
                    }
                });
            }
        } catch (RuntimeException error) {
            log.warn("event=competition_immediate_start_deferred competitionId={} causeType={}",
                    result.getCompetitionId(), error.getClass().getSimpleName(), error);
        }
        try {
            var c = competitions.findByIdAndUserId(result.getCompetitionId(), userId).orElseThrow();
            return new CompetitionCreateResponse("success", c.getId(), c.getState().name().toLowerCase(Locale.ROOT));
        } catch (RuntimeException error) {
            // 생성 확정 후의 조회 실패를 생성 실패로 오인하게 하지 않는다. 실제 상태는 목록에서 재확인한다.
            log.warn("event=competition_creation_state_read_deferred competitionId={} causeType={}",
                    result.getCompetitionId(), error.getClass().getSimpleName());
            return result;
        }
    }
}
