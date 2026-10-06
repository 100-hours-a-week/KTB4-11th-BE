package com.stock_spoon.river_be.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

public record OrderCreateResponse(
        String message,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("account_id") long accountId,
        @JsonProperty("stock_code") String stockCode,
        @JsonProperty("stock_name") String stockName,
        @JsonProperty("order_side") String orderSide,
        @JsonProperty("order_type") String orderType,
        @JsonProperty("limit_price") Long limitPrice,
        long quantity,
        @JsonProperty("order_status") String orderStatus,
        @JsonProperty("reserved_cash") long reservedCash,
        @JsonProperty("created_at") Instant createdAt,
        List<ExecutionItem> executions) {

    // 주문·체결 값을 응답용 record로 옮긴다. JSON 변환은 Controller 반환 이후 Spring이 처리한다.
    static OrderCreateResponse from(Order order, List<Execution> executions) {
        return new OrderCreateResponse("success", order.getId(), order.getAccountId(),
                order.getStockCode(), order.getReport() == null ? null : order.getReport().getStockName(), order.getSide().name().toLowerCase(java.util.Locale.ROOT),
                order.getType().name().toLowerCase(java.util.Locale.ROOT), order.getLimitPrice(),
                order.getQuantity(), order.getStatus().name().toLowerCase(java.util.Locale.ROOT),
                order.getReservedCash(), order.getCreatedAt(), executions.stream().map(ExecutionItem::from).toList());
    }

    public record ExecutionItem(
            @JsonProperty("execution_id") long executionId,
            @JsonProperty("execution_price") long executionPrice,
            @JsonProperty("execution_quantity") long executionQuantity,
            @JsonProperty("realized_pnl") java.math.BigDecimal realizedPnl,
            @JsonProperty("realized_return_percent") java.math.BigDecimal realizedReturnPercent,
            @JsonProperty("created_at") Instant createdAt) {
        static ExecutionItem from(Execution execution) {
            return new ExecutionItem(execution.getId(), execution.getPrice(), execution.getQuantity(),
                    execution.getRealizedPnl(), execution.getRealizedReturnPercent(), execution.getCreatedAt());
        }
    }
}
