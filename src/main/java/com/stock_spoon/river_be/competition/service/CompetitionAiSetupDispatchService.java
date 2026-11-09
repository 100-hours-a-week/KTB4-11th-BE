package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.competition.messaging.CompetitionAiSetupRequestPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "competition.ai-setup.queue.enabled", havingValue = "true")
public class CompetitionAiSetupDispatchService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompetitionAiSetupDispatchService.class);
    private final jakarta.persistence.EntityManager entityManager;
    private final CompetitionRepository competitions;
    private final CompetitionAiSetupOutboxRepository outboxes;
    private final CompetitionAiSetupRequestPublisher publisher;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String dispatch(Long id) {
        var candidate = outboxes.findById(id).orElse(null);
        if (candidate == null) return null;
        // Cancellation uses the same competition-first lock order.
        var competition = competitions.findLockedById(candidate.getCompetition().getId()).orElseThrow();
        var outbox = outboxes.findLockedById(id).orElseThrow();
        entityManager.refresh(outbox);
        if (outbox.getStatus() != CompetitionAiSetupOutbox.Status.PENDING) return null;
        if (competition.getState() == Competition.State.CANCELLED) {
            log.debug("event=competition_ai_setup_skipped stage=competition_cancelled outboxId={} competitionId={} eventId={}",
                    id, competition.getId(), outbox.getEventId());
            outbox.markCancelled();
            return null;
        }
        if (competition.getCompetitionType() != Competition.Type.AI_VS_AI
                || competition.getState() != Competition.State.READY) {
            throw new IllegalStateException("Only READY AI competitions can be configured");
        }
        String stage = "sqs_send";
        try {
            log.debug("event=competition_ai_setup_publish_started outboxId={} competitionId={} eventId={}",
                    id, competition.getId(), outbox.getEventId());
            publisher.publish(outbox.getPayload());
            log.info("event=competition_ai_setup_published outboxId={} competitionId={} eventId={}",
                    id, competition.getId(), outbox.getEventId());
            stage = "sent_save";
            outbox.markSent();
            outboxes.flush();
        } catch (RuntimeException error) {
            log.error("event=competition_ai_setup_dispatch_failed stage={} outboxId={} competitionId={} eventId={} causeType={}",
                    stage, id, competition.getId(), outbox.getEventId(), error.getClass().getSimpleName());
            throw error;
        }
        return outbox.getEventId();
    }
}
