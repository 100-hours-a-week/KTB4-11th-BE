package com.stock_spoon.river_be.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
