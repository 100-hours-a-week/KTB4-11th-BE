package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.competition.dto.CompetitionTradeHistoryCursor;
import com.stock_spoon.river_be.competition.exception.CompetitionException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class CompetitionTradeHistoryCursorTests {
    @Test void bindsCursorToSameFilterIncludingUnfilteredRequests() {
        for (String saved : java.util.Arrays.asList(null, "buy", "sell")) {
            var encoded = new CompetitionTradeHistoryCursor(Instant.parse("2026-10-21T00:00:00Z"), 1, 45, saved).encode();
            assertThat(CompetitionTradeHistoryCursor.decode(encoded, 45, saved).getOrderSide()).isEqualTo(saved);
            for (String requested : java.util.Arrays.asList(null, "buy", "sell")) {
                if (!java.util.Objects.equals(saved, requested)) {
                    assertThatThrownBy(() -> CompetitionTradeHistoryCursor.decode(encoded, 45, requested))
                            .isInstanceOf(CompetitionException.class);
                }
            }
        }
    }

    @Test void acceptsLegacyCursorOnlyWithoutFilter() {
        String payload = "{\"executed_at\":\"2026-10-21T00:00:00Z\",\"order_id\":1,\"account_id\":45}";
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        assertThat(CompetitionTradeHistoryCursor.decode(encoded, 45, null).getOrderSide()).isNull();
        for (String side : java.util.List.of("buy", "sell")) {
            assertThatThrownBy(() -> CompetitionTradeHistoryCursor.decode(encoded, 45, side))
                    .isInstanceOf(CompetitionException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "\"BUY\"", "\"unknown\"", "true", "[]", "{}"})
    void rejectsInvalidFilterInPayload(String filter) {
        String payload = "{\"executed_at\":\"2026-10-21T00:00:00Z\",\"order_id\":1,\"account_id\":45,\"order_side\":" + filter + "}";
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> CompetitionTradeHistoryCursor.decode(encoded, 45, null))
                .isInstanceOf(CompetitionException.class);
    }

    @Test void preservesTimestampPrecisionAndRejectsDifferentAccount() {
        var cursor = new CompetitionTradeHistoryCursor(Instant.parse("2026-10-21T06:30:00.123456Z"), 123, 45, null);
        var decoded = CompetitionTradeHistoryCursor.decode(cursor.encode(), 45, null);
        assertThat(decoded.getExecutedAt()).isEqualTo(cursor.getExecutedAt());
        assertThat(decoded.getOrderId()).isEqualTo(123);
        assertThat(decoded.getAccountId()).isEqualTo(45);
        assertThat(cursor.encode()).matches("[A-Za-z0-9_-]+");
        assertThatThrownBy(() -> CompetitionTradeHistoryCursor.decode(cursor.encode(), 46, null))
                .isInstanceOf(CompetitionException.class);
        assertThat(CompetitionTradeHistoryCursor.decode(null, 45, null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "null", "not-json",
            "{\"executed_at\":\"bad\",\"order_id\":1,\"account_id\":45}",
            "{\"executed_at\":\"2026-10-21T00:00:00Z\",\"order_id\":0,\"account_id\":45}",
            "{\"executed_at\":\"2026-10-21T00:00:00Z\",\"order_id\":1.5,\"account_id\":45}",
            "{\"executed_at\":\"2026-10-21T00:00:00Z\",\"order_id\":9223372036854775808,\"account_id\":45}",
            "{\"executed_at\":\"2026-10-21T00:00:00Z\",\"order_id\":1,\"account_id\":\"45\"}"})
    void rejectsMalformedPayload(String payload) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> CompetitionTradeHistoryCursor.decode(encoded, 45, null))
                .isInstanceOfSatisfying(CompetitionException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVALID_TRADE_HISTORY_CURSOR"));
    }
}
