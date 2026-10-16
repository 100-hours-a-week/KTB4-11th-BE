package com.stock_spoon.river_be.competition.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CompetitionTradeHistoryResponse {
    @JsonProperty("account_id")
    private Long accountId;
    private List<Item> executions;

    // 항목 하나는 주문 하나의 합산 결과다. 개별 체결 ID 대신 order_id로 식별한다.
    @Getter
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Item {
        @JsonProperty("order_id")
        private Long orderId;
        @JsonProperty("stock_code")
        private String stockCode;
        @JsonProperty("stock_name")
        private String stockName;
        @JsonProperty("order_side")
        private String orderSide;
        @JsonProperty("executed_at")
        private OffsetDateTime executedAt;
        @JsonProperty("execution_quantity")
        private long executionQuantity;
        @JsonProperty("execution_amount")
        private long executionAmount;
        @JsonProperty("realized_profit_amount")
        private BigDecimal realizedProfitAmount;
        @JsonProperty("realized_return_percent")
        private BigDecimal realizedReturnPercent;
    }
}
