package com.stock_spoon.river_be.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record AccountHoldingDetailResponse(
        @JsonProperty("account_id") long accountId,
        @JsonProperty("stock_code") String stockCode,
        @JsonProperty("is_held") boolean held,
        long quantity,
        @JsonProperty("total_cost") BigDecimal totalCost,
        @JsonProperty("average_purchase_price") BigDecimal averagePurchasePrice) {}
