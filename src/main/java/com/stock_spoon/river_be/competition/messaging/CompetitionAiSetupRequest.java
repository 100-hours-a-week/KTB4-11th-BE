package com.stock_spoon.river_be.competition.messaging;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public record CompetitionAiSetupRequest(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("competition_id") Long competitionId,
        @JsonProperty("user_id") Long userId,
        List<Agent> agents) {
    public record Agent(
            @JsonProperty("participant_id") Long participantId,
            @JsonProperty("account_id") Long accountId,
            @JsonProperty("risk_level") int riskLevel,
            @JsonProperty("preferred_themes") List<String> preferredThemes,
            @JsonProperty("excluded_themes") List<String> excludedThemes,
            @JsonProperty("additional_request") String additionalRequest) {}
}
