package com.stock_spoon.river_be.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/** 홈과 전체 페이지는 같은 응답을 사용하며 배열의 개수만 달라진다. */
public record AccountHoldingsResponse(String message, @JsonProperty("account_id") long accountId,
                                      List<Item> holdings) {
    public record Item(
            @JsonProperty("stock_code") String stockCode,
            @JsonProperty("stock_name") String stockName,
            String sector,
            long quantity,
            @JsonProperty("total_cost") BigDecimal totalCost,
            @JsonProperty("average_purchase_price") BigDecimal averagePurchasePrice,
            @JsonProperty("current_price") long currentPrice,
            @JsonProperty("total_value") long totalValue,
            @JsonProperty("unrealized_pnl") BigDecimal unrealizedPnl,
            @JsonProperty("return_percent") BigDecimal returnPercent) {}
}
