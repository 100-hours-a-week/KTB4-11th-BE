package com.stock_spoon.river_be.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record OrderHistoryResponse(@JsonProperty("account_id") long accountId, List<Item> orders) {
    public record Item(
            @JsonProperty("order_id") long orderId,
            @JsonProperty("stock_code") String stockCode,
            @JsonProperty("stock_name") String stockName,
            @JsonProperty("order_source") String orderSource,
            @JsonProperty("order_side") String orderSide,
            @JsonProperty("order_type") String orderType,
            @JsonProperty("order_status") String orderStatus,
            long quantity,
            @JsonProperty("limit_price") Long limitPrice,
            @JsonProperty("reserved_cash") long reservedCash,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("canceled_at") OffsetDateTime canceledAt,
            Reason reason,
            List<ExecutionItem> executions,
            @JsonProperty("execution_summary") ExecutionSummary executionSummary,
            @JsonProperty("can_cancel") boolean canCancel) {
        public Item withStockName(String name) {
            return new Item(orderId, stockCode, name, orderSource, orderSide, orderType, orderStatus,
                    quantity, limitPrice, reservedCash, createdAt, canceledAt, reason, executions,
                    executionSummary, canCancel);
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Reason(String summary) {}
    public record ExecutionItem(
            @JsonProperty("execution_id") long executionId,
            @JsonProperty("execution_price") long executionPrice,
            @JsonProperty("execution_quantity") long executionQuantity,
            @JsonProperty("realized_pnl") BigDecimal realizedPnl,
            @JsonProperty("realized_return_percent") BigDecimal realizedReturnPercent,
            @JsonProperty("created_at") OffsetDateTime createdAt) {}
    public record ExecutionSummary(long quantity,
            @JsonProperty("average_price") long averagePrice,
            @JsonProperty("total_amount") long totalAmount,
            @JsonProperty("executed_at") OffsetDateTime executedAt,
            @JsonProperty("realized_pnl") BigDecimal realizedPnl,
            @JsonProperty("realized_return_percent") BigDecimal realizedReturnPercent) {}
}
