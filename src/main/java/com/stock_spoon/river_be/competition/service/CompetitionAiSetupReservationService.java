package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.competition.messaging.CompetitionAiSetupRequest;
import com.stock_spoon.river_be.competition.logging.CompetitionCreationLog;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class CompetitionAiSetupReservationService {
    private final CompetitionAgentRepository agents;
    private final CompetitionAiSetupOutboxRepository outboxes;
    private final JsonMapper json;

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(Competition competition) {
        String eventId = UUID.randomUUID().toString();
        CompetitionCreationLog.stage("ai_setup_strategy_lookup");
        var settings = agents.findAllByParticipantCompetitionIdOrderByPositionAsc(competition.getId()).stream()
                .map(a -> new CompetitionAiSetupRequest.Agent(a.getParticipant().getId(),
                        a.getParticipant().getAccount().getId(), a.getRiskLevel(),
                        List.copyOf(a.getPreferredThemes()), List.copyOf(a.getExcludedThemes()),
                        a.getAdditionalRequest())).toList();
        CompetitionCreationLog.stage("ai_setup_strategy_validation");
        if (settings.size() != competition.getAiParticipantCount()) {
            throw new IllegalStateException("AI strategy count mismatch");
        }
        CompetitionCreationLog.stage("ai_setup_message_encode");
        var message = new CompetitionAiSetupRequest(eventId, "competition_ai_setup",
                competition.getId(), competition.getUser().getId(), settings);
        String payload = json.writeValueAsString(message);
        CompetitionCreationLog.stage("ai_setup_outbox_save");
        outboxes.save(new CompetitionAiSetupOutbox(competition, eventId, payload));
    }
}
