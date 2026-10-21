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
    @Test void preservesTimestampPrecisionAndRejectsDifferentAccount() {
        var cursor = new CompetitionTradeHistoryCursor(Instant.parse("2026-10-21T06:30:00.123456Z"), 123, 45);
        var decoded = CompetitionTradeHistoryCursor.decode(cursor.encode(), 45);
        assertThat(decoded.getExecutedAt()).isEqualTo(cursor.getExecutedAt());
        assertThat(decoded.getOrderId()).isEqualTo(123);
        assertThat(decoded.getAccountId()).isEqualTo(45);
        assertThat(cursor.encode()).matches("[A-Za-z0-9_-]+");
        assertThatThrownBy(() -> CompetitionTradeHistoryCursor.decode(cursor.encode(), 46))
                .isInstanceOf(CompetitionException.class);
        assertThat(CompetitionTradeHistoryCursor.decode(null, 45)).isNull();
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
        assertThatThrownBy(() -> CompetitionTradeHistoryCursor.decode(encoded, 45))
                .isInstanceOfSatisfying(CompetitionException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVALID_TRADE_HISTORY_CURSOR"));
    }
}
