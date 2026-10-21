package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.competition.dto.CompetitionListCursor;
import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.exception.CompetitionException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;

class CompetitionListCursorTests {
    @Test
    void preservesDatabaseTimePrecisionAndFilterThroughUrlSafeCursor() {
        var time = Instant.parse("2026-10-21T01:02:03.123456Z");
        var original = new CompetitionListCursor(time, 123L, Competition.State.IN_PROGRESS);
        String encoded = original.encode();
        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        var decoded = CompetitionListCursor.decode(encoded, Competition.State.IN_PROGRESS);
        assertThat(decoded.getCreatedAt()).isEqualTo(time);
        assertThat(decoded.getCompetitionId()).isEqualTo(123L);
        assertThat(decoded.getState()).isEqualTo(Competition.State.IN_PROGRESS);
    }

    @Test
    void supportsUnfilteredCursorAndFirstRequestWithoutCursor() {
        assertThat(CompetitionListCursor.decode(null, null)).isNull();
        var original = new CompetitionListCursor(Instant.parse("2026-10-21T00:00:00Z"), 1L, null);
        assertThat(CompetitionListCursor.decode(original.encode(), null).getState()).isNull();
    }

    @Test
    void rejectsCursorFromDifferentFilter() {
        var cursor = new CompetitionListCursor(Instant.parse("2026-10-21T00:00:00Z"), 1L, Competition.State.COMPLETED).encode();
        assertInvalid(cursor, Competition.State.IN_PROGRESS);
        assertInvalid(cursor, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not+url/safe", "%%%"})
    void rejectsInvalidEncodedInput(String cursor) { assertInvalid(cursor, null); }

    @Test
    void rejectsOversizedCursorBeforeDecoding() { assertInvalid("a".repeat(513), null); }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json", "[]", "{}",
            "{\"created_at\":\"wrong-date\",\"competition_id\":1,\"state\":null}",
            "{\"created_at\":\"2026-10-21T00:00:00Z\",\"competition_id\":0,\"state\":null}",
            "{\"created_at\":\"2026-10-21T00:00:00Z\",\"competition_id\":-1,\"state\":null}",
            "{\"created_at\":\"2026-10-21T00:00:00Z\",\"competition_id\":1.5,\"state\":null}",
            "{\"created_at\":\"2026-10-21T00:00:00Z\",\"competition_id\":9223372036854775808,\"state\":null}",
            "{\"created_at\":\"2026-10-21T00:00:00Z\",\"competition_id\":1,\"state\":\"unknown\"}"
    })
    void rejectsMalformedPayloadWithoutEchoingInput(String json) {
        assertInvalid(Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8)), null);
    }

    private void assertInvalid(String cursor, Competition.State state) {
        assertThatThrownBy(() -> CompetitionListCursor.decode(cursor, state))
                .isInstanceOfSatisfying(CompetitionException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getCode()).isEqualTo("INVALID_COMPETITION_CURSOR");
                    assertThat(error.getMessage()).isEqualTo("대결 목록 커서가 올바르지 않습니다.");
                    assertThat(error.getCause()).isNull();
                });
    }
}
