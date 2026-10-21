package com.stock_spoon.river_be.competition.dto;

import com.stock_spoon.river_be.competition.exception.CompetitionException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Getter
@AllArgsConstructor
public class CompetitionTradeHistoryCursor {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Instant executedAt;
    private final long orderId;
    private final long accountId;

    public String encode() {
        var payload = JSON.createObjectNode();
        // DB에서 읽은 시각의 정밀도를 유지하고, 같은 시각의 주문은 ID로 구분한다.
        payload.put("executed_at", executedAt.toString());
        payload.put("order_id", orderId);
        payload.put("account_id", accountId);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(payload));
    }

    public static CompetitionTradeHistoryCursor decode(String cursor, long expectedAccountId) {
        if (cursor == null) return null;
        if (cursor.isEmpty() || cursor.length() > 512 || !cursor.matches("[A-Za-z0-9_-]+")) throw invalid();
        try {
            var payload = JSON.readTree(Base64.getUrlDecoder().decode(cursor));
            if (payload == null || !payload.isObject() || payload.size() != 3) throw invalid();
            var time = payload.get("executed_at");
            var order = payload.get("order_id");
            var account = payload.get("account_id");
            if (time == null || !time.isTextual() || order == null || !order.isIntegralNumber()
                    || !order.canConvertToLong() || order.longValue() <= 0
                    || account == null || !account.isIntegralNumber() || !account.canConvertToLong()
                    || account.longValue() <= 0 || account.longValue() != expectedAccountId) throw invalid();
            return new CompetitionTradeHistoryCursor(Instant.parse(time.asText()), order.longValue(), account.longValue());
        } catch (IllegalArgumentException | DateTimeException | JacksonException error) {
            throw invalid();
        }
    }

    private static CompetitionException invalid() {
        return new CompetitionException(HttpStatus.BAD_REQUEST, "INVALID_TRADE_HISTORY_CURSOR", "매매 내역 커서가 올바르지 않습니다.");
    }
}
