package com.stock_spoon.river_be.competition.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import java.util.List;

public record CompetitionAgentRequest(
        @JsonProperty("risk_level") @NotNull @Min(1) @Max(5) Integer riskLevel,
        @JsonProperty("preferred_themes") @NotNull List<@NotBlank @Size(max = 255) String> preferredThemes,
        @JsonProperty("excluded_themes") @NotNull List<@NotBlank @Size(max = 255) String> excludedThemes,
        @JsonProperty("additional_request") @Size(max = 4000) String additionalRequest) {}
