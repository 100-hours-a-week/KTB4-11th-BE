package com.stock_spoon.river_be.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

public record OrderCreateResponse(
        String message,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("account_id") long accountId,
        @JsonProperty("stock_code") String stockCode,
        @JsonProperty("order_side") String orderSide,
        @JsonProperty("order_type") String orderType,
        @JsonProperty("limit_price") Long limitPrice,
        long quantity,
        @JsonProperty("order_status") String orderStatus,
        @JsonProperty("reserved_cash") long reservedCash,
        @JsonProperty("created_at") Instant createdAt,
        List<Object> executions) {

    static OrderCreateResponse from(Order order) {
        return new OrderCreateResponse("success", order.getId(), order.getAccountId(),
                order.getStockCode(), order.getSide().name().toLowerCase(java.util.Locale.ROOT),
                order.getType().name().toLowerCase(java.util.Locale.ROOT), order.getLimitPrice(),
                order.getQuantity(), order.getStatus().name().toLowerCase(java.util.Locale.ROOT),
                order.getReservedCash(), order.getCreatedAt(), List.of());
    }
}
