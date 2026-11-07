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
            outbox.markCancelled();
            return null;
        }
        if (competition.getCompetitionType() != Competition.Type.AI_VS_AI
                || competition.getState() != Competition.State.READY) {
            throw new IllegalStateException("Only READY AI competitions can be configured");
        }
        publisher.publish(outbox.getPayload());
        outbox.markSent();
        outboxes.flush();
        return outbox.getEventId();
    }
}
