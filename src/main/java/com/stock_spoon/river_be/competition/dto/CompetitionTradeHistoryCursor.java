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
    private final String orderSide;

    public String encode() {
        var payload = JSON.createObjectNode();
        // DB에서 읽은 시각의 정밀도를 유지하고, 같은 시각의 주문은 ID로 구분한다.
        payload.put("executed_at", executedAt.toString());
        payload.put("order_id", orderId);
        payload.put("account_id", accountId);
        // 전체 조회 조건도 null로 명시해 매수·매도 커서와 구분한다.
        if (orderSide == null) payload.putNull("order_side");
        else payload.put("order_side", orderSide);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(payload));
    }

    public static CompetitionTradeHistoryCursor decode(String cursor, long expectedAccountId, String expectedOrderSide) {
        if (cursor == null) return null;
        if (cursor.isEmpty() || cursor.length() > 512 || !cursor.matches("[A-Za-z0-9_-]+")) throw invalid();
        try {
            var payload = JSON.readTree(Base64.getUrlDecoder().decode(cursor));
            if (payload == null || !payload.isObject()) throw invalid();
            var side = payload.get("order_side");
            // 필터 도입 전 3필드 커서는 필터 없는 전체 조회에서만 이어 쓸 수 있다.
            boolean legacy = side == null && payload.size() == 3;
            if (!legacy && (side == null || payload.size() != 4 || (!side.isNull() && !side.isTextual()))) throw invalid();
            String orderSide = legacy || side.isNull() ? null : side.asText();
            if (orderSide != null && !"buy".equals(orderSide) && !"sell".equals(orderSide)) throw invalid();
            if (!java.util.Objects.equals(orderSide, expectedOrderSide)) throw invalid();
            var time = payload.get("executed_at");
            var order = payload.get("order_id");
            var account = payload.get("account_id");
            if (time == null || !time.isTextual() || order == null || !order.isIntegralNumber()
                    || !order.canConvertToLong() || order.longValue() <= 0
                    || account == null || !account.isIntegralNumber() || !account.canConvertToLong()
                    || account.longValue() <= 0 || account.longValue() != expectedAccountId) throw invalid();
            return new CompetitionTradeHistoryCursor(Instant.parse(time.asText()), order.longValue(), account.longValue(), orderSide);
        } catch (IllegalArgumentException | DateTimeException | JacksonException error) {
            throw invalid();
        }
    }

    private static CompetitionException invalid() {
        return new CompetitionException(HttpStatus.BAD_REQUEST, "INVALID_TRADE_HISTORY_CURSOR", "매매 내역 커서가 올바르지 않습니다.");
    }
}
