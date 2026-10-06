package com.stock_spoon.river_be.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// HTTP JSON 입력용 DTO이며 DB Entity가 아니다.
// @JsonProperty는 stock_code 같은 JSON 이름을 stockCode 같은 Java 이름에 연결한다.
// record는 request.stockCode(), request.quantity()처럼 값을 읽는 접근자를 제공한다.
public record OrderCreateRequest(
        @JsonProperty("stock_code") @NotBlank @Pattern(regexp = "[0-9]{6}") String stockCode,
        @JsonProperty("order_side") @NotBlank String orderSide,
        @JsonProperty("order_type") @NotBlank String orderType,
        @JsonProperty("limit_price") Long limitPrice,
        @NotNull @Min(1) Long quantity,
        @NotBlank @Size(max = 100000) String reason,
        @JsonProperty("stock_name") @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}].*") @Size(max = 255) String stockName,
        java.util.List<@NotNull @jakarta.validation.Valid ReportReasoning> reasoning,
        @JsonProperty("holding_weight_limit_percent") Double holdingWeightLimitPercent,
        @JsonProperty("is_lower_triggered")
        @tools.jackson.databind.annotation.JsonDeserialize(using = StrictBoolean.class) Boolean isLowerTriggered) {

    // 단일 필드 제약 외에 매수·매도 방향에 따라 필수값이 달라지는 규칙을 Controller에서 호출한다.
    void validateReportInputs(Order.Side side) {
        if (holdingWeightLimitPercent != null && (!Double.isFinite(holdingWeightLimitPercent)
                || holdingWeightLimitPercent < 0 || holdingWeightLimitPercent > 100)) {
            throw new OrderException("holding_weight_limit_percent는 0~100의 숫자여야 합니다.");
        }
        if (side == Order.Side.BUY && holdingWeightLimitPercent == null) {
            throw new OrderException("매수 주문의 holding_weight_limit_percent를 입력하세요.");
        }
        if (side == Order.Side.SELL && isLowerTriggered == null) {
            throw new OrderException("매도 주문의 is_lower_triggered를 입력하세요.");
        }
    }

    public static class StrictBoolean extends tools.jackson.databind.ValueDeserializer<Boolean> {
        @Override
        public Boolean deserialize(tools.jackson.core.JsonParser parser,
                tools.jackson.databind.DeserializationContext context) throws tools.jackson.core.JacksonException {
            if (parser.currentToken() == tools.jackson.core.JsonToken.VALUE_TRUE) return Boolean.TRUE;
            if (parser.currentToken() == tools.jackson.core.JsonToken.VALUE_FALSE) return Boolean.FALSE;
            return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
        }
    }
}
