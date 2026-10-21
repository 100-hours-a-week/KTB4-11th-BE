package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.competition.dto.CompetitionListResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class CompetitionListResponseTests {
    private final JsonMapper json = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void serializesCursorPaginationAndKeepsNullOnLastPage(boolean hasNext) {
        String cursor = hasNext ? "next-cursor" : null;
        var response = new CompetitionListResponse(12, List.of(),
                new CompetitionListResponse.Pagination(10, hasNext, cursor));
        var body = json.readTree(json.writeValueAsString(response));
        assertThat(body.get("ongoing_competition_count").asLong()).isEqualTo(12);
        var pagination = body.get("pagination");
        assertThat(pagination.get("limit").asInt()).isEqualTo(10);
        assertThat(pagination.get("has_next").asBoolean()).isEqualTo(hasNext);
        assertThat(pagination.has("next_cursor")).isTrue();
        if (hasNext) assertThat(pagination.get("next_cursor").asText()).isEqualTo(cursor);
        else assertThat(pagination.get("next_cursor").isNull()).isTrue();
        assertThat(pagination.size()).isEqualTo(3);
        assertThat(pagination.has("page")).isFalse();
    }

    @Test
    void includesPaginationEvenForEmptyResponse() {
        var body = json.readTree(json.writeValueAsString(new CompetitionListResponse(0, List.of(),
                new CompetitionListResponse.Pagination(10, false, null))));
        assertThat(body.has("ongoing_competition_count")).isTrue();
        assertThat(body.get("competitions").isEmpty()).isTrue();
        assertThat(body.at("/pagination/has_next").asBoolean()).isFalse();
        assertThat(body.at("/pagination/next_cursor").isNull()).isTrue();
    }
}
