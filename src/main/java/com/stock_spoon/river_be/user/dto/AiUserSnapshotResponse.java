package com.stock_spoon.river_be.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record AiUserSnapshotResponse(List<UserSnapshot> users) {
    public record UserSnapshot(@JsonProperty("user_id") long userId,
            List<AccountSnapshot> accounts) {}

    public record AccountSnapshot(@JsonProperty("account_id") long accountId,
            @JsonProperty("account_name") String accountName,
            @JsonProperty("is_active") boolean active,
            @JsonProperty("cash_balance") long cashBalance,
            List<StockSnapshot> stocks,
            @JsonProperty("pending_orders") List<PendingOrderSnapshot> pendingOrders) {}

    public record StockSnapshot(@JsonProperty("stock_code") String stockCode,
            @JsonProperty("total_cost") BigDecimal totalCost, long quantity) {}

    public record PendingOrderSnapshot(@JsonProperty("order_id") long orderId,
            @JsonProperty("stock_code") String stockCode,
            @JsonProperty("order_side") String side,
            @JsonProperty("order_status") String status,
            @JsonProperty("order_type") String type,
            @JsonProperty("limit_price") Long limitPrice, long quantity) {}
}
