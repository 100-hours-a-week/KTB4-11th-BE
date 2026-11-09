package com.stock_spoon.river_be.order.dto;

import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.report.entity.ReportReasoning;
import com.stock_spoon.river_be.order.exception.OrderException;

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
        @io.swagger.v3.oas.annotations.media.Schema(example = "005930")
        @JsonProperty("stock_code") @NotBlank @Pattern(regexp = "[0-9]{6}") String stockCode,
        @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {"buy", "sell"}, example = "buy")
        @JsonProperty("order_side") @NotBlank String orderSide,
        @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {"market", "limit"}, example = "limit")
        @JsonProperty("order_type") @NotBlank String orderType,
        @io.swagger.v3.oas.annotations.media.Schema(description = "지정가 주문 가격", example = "70000")
        @JsonProperty("limit_price") Long limitPrice,
        @NotNull @Min(1) Long quantity,
        @Size(max = 100000) String reason,
        @JsonProperty("stock_name") @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}].*") @Size(max = 255) String stockName,
        java.util.List<@NotNull @jakarta.validation.Valid ReportReasoning> reasoning,
        @io.swagger.v3.oas.annotations.media.Schema(description = "매수 시 필수, 0~100", example = "20")
        @JsonProperty("holding_weight_limit_percent") Double holdingWeightLimitPercent,
        @io.swagger.v3.oas.annotations.media.Schema(description = "매도 시 필수", example = "false")
        @JsonProperty("is_lower_triggered")
        @tools.jackson.databind.annotation.JsonDeserialize(using = StrictBoolean.class) Boolean isLowerTriggered,
        // AI 판단 근거의 원본 ID다. @Pattern은 null을 허용하고 전달된 값의 UUID 형식만 검사한다.
        @JsonProperty("reason_id")
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String reasonId) {

    // 기존 Java 호출부는 reason_id가 없는 요청으로 유지한다. null 필드는 직렬화에서도 생략해 기존 해시를 보존한다.
    public OrderCreateRequest(String stockCode, String orderSide, String orderType, Long limitPrice,
            Long quantity, String reason, String stockName, java.util.List<ReportReasoning> reasoning,
            Double holdingWeightLimitPercent, Boolean isLowerTriggered) {
        this(stockCode, orderSide, orderType, limitPrice, quantity, reason, stockName, reasoning,
                holdingWeightLimitPercent, isLowerTriggered, null);
    }

    // 단일 필드 제약 외에 매수·매도 방향에 따라 필수값이 달라지는 규칙을 Controller에서 호출한다.
    public void validateReportInputs(Order.Side side) {
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
