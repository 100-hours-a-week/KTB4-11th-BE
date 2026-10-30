package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.competition.dto.CompetitionReportSaveRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class CompetitionReportSaveRequestTests {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void acceptsEmptyArraysAndRejectsMissingOrInvalidItems() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(read("{\"news\":[],\"thoughts\":[]} "))).isEmpty();
            assertThat(validator.validate(read("{\"news\":[{\"title\":\"제목\",\"summary\":\"요약\"}],\"thoughts\":[{\"title\":\"생각\",\"text\":\"내용\"}]}"))).isEmpty();
            assertThat(validator.validate(read("{\"news\":[],\"thoughts\":null}"))).isNotEmpty();
            assertThat(validator.validate(read("{\"news\":[null],\"thoughts\":[]}"))).isNotEmpty();
            assertThat(validator.validate(read("{\"news\":[{\"title\":\"  \",\"summary\":\"요약\"}],\"thoughts\":[]}"))).isNotEmpty();
            assertThat(validator.validate(read("{\"news\":[],\"thoughts\":[{\"title\":\"생각\",\"text\":\"\"}]}"))).isNotEmpty();
            String longTitle = "가".repeat(256);
            assertThat(validator.validate(read("{\"news\":[{\"title\":\"" + longTitle + "\",\"summary\":\"요약\"}],\"thoughts\":[]}"))).isNotEmpty();
        }
    }

    private CompetitionReportSaveRequest read(String body) {
        return json.readValue(body, CompetitionReportSaveRequest.class);
    }
}
