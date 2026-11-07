package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.competition.messaging.CompetitionAiSetupRequest;
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
        var settings = agents.findAllByParticipantCompetitionIdOrderByPositionAsc(competition.getId()).stream()
                .map(a -> new CompetitionAiSetupRequest.Agent(a.getParticipant().getId(),
                        a.getParticipant().getAccount().getId(), a.getRiskLevel(),
                        List.copyOf(a.getPreferredThemes()), List.copyOf(a.getExcludedThemes()),
                        a.getAdditionalRequest())).toList();
        if (settings.size() != competition.getAiParticipantCount()) {
            throw new IllegalStateException("AI strategy count mismatch");
        }
        var message = new CompetitionAiSetupRequest(eventId, "competition_ai_setup", competition.getId(), settings);
        outboxes.save(new CompetitionAiSetupOutbox(competition, eventId, json.writeValueAsString(message)));
    }
}
