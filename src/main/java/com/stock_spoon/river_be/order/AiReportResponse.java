package com.stock_spoon.river_be.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiReportResponse(
        @JsonProperty("order_id") long orderId,
        @JsonProperty("order_side") String orderSide,
        @JsonProperty("stock_code") String stockCode,
        @JsonProperty("stock_name") String stockName,
        @JsonProperty("holding_weight_after_trade_percent") Double holdingWeightAfterTradePercent,
        @JsonProperty("holding_weight_limit_percent") Double holdingWeightLimitPercent,
        ExecutionSummary execution,
        String reason,
        List<ReportReasoning> reasoning,
        @JsonProperty("sell_result") SellResult sellResult) {

    static AiReportResponse from(Order order, List<Execution> executions) {
        return from(order, executions, null);
    }

    static AiReportResponse from(Order order, List<Execution> executions, Integer holdingDays) {
        var report = order.getReport();
        boolean selling = order.getSide() == Order.Side.SELL;
        ExecutionSummary execution = null;
        BigDecimal buyPrice = null;
        BigDecimal realizedPnl = null;
        BigDecimal returnPercent = null;
        if (!executions.isEmpty()) {
            long quantity = 0;
            BigDecimal amount = BigDecimal.ZERO;
            BigDecimal pnl = BigDecimal.ZERO;
            boolean completePnl = true;
            Instant executedAt = executions.getFirst().getCreatedAt();
            for (var item : executions) {
                quantity = Math.addExact(quantity, item.getQuantity());
                amount = amount.add(BigDecimal.valueOf(item.getPrice()).multiply(BigDecimal.valueOf(item.getQuantity())));
                if (item.getCreatedAt().isAfter(executedAt)) executedAt = item.getCreatedAt();
                if (item.getRealizedPnl() == null) completePnl = false;
                else pnl = pnl.add(item.getRealizedPnl());
            }
            var price = amount.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
            execution = new ExecutionSummary(seoul(executedAt), price, quantity, amount);
            if (selling && completePnl) {
                realizedPnl = pnl;
                // 저장 손익이 소수2자리이므로 역산 원가는 그 정밀도에 한정된다.
                var cost = amount.subtract(pnl);
                if (cost.signum() > 0) {
                    buyPrice = cost.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
                    returnPercent = pnl.multiply(BigDecimal.valueOf(100)).divide(cost, 4, RoundingMode.HALF_UP);
                }
            }
        }
        var sell = selling ? new SellResult(holdingDays, buyPrice, realizedPnl, returnPercent,
                null, null, report.getIsLowerTriggered()) : null;
        return new AiReportResponse(order.getId(), order.getSide().name().toLowerCase(Locale.ROOT),
                order.getStockCode(), report.getStockName(), selling ? null : report.getHoldingWeightAfterTradePercent(),
                selling ? null : report.getHoldingWeightLimitPercent(), execution,
                report.getReason(), report.getReasoning(), sell);
    }

    public record ExecutionSummary(
            @JsonProperty("executed_at") OffsetDateTime executedAt,
            @JsonProperty("execution_price") BigDecimal executionPrice,
            @JsonProperty("execution_quantity") long executionQuantity,
            @JsonProperty("trade_amount") BigDecimal tradeAmount) {}

    private static OffsetDateTime seoul(Instant time) {
        return time.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SellResult(
            @JsonProperty("holding_days") Integer holdingDays,
            @JsonProperty("average_buy_price") BigDecimal averageBuyPrice,
            @JsonProperty("realized_pnl") BigDecimal realizedPnl,
            @JsonProperty("realized_return_percent") BigDecimal realizedReturnPercent,
            @JsonProperty("target_return_percent") Double targetReturnPercent,
            @JsonProperty("target_reached") Boolean targetReached,
            @JsonProperty("stop_loss_triggered") Boolean stopLossTriggered) {}
}
