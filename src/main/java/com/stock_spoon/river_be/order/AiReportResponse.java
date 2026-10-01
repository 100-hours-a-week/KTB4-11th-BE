package com.stock_spoon.river_be.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.List;
import java.util.Locale;

public record AiReportResponse(
        String message,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("order_side") String orderSide,
        @JsonProperty("stock_code") String stockCode,
        @JsonProperty("stock_name") String stockName,
        @JsonProperty("report_status") String reportStatus,
        @JsonProperty("decided_at") OffsetDateTime decidedAt,
        String summary,
        ExecutionSummary execution,
        @JsonProperty("buy_analysis") Map<String, Object> buyAnalysis,
        @JsonProperty("sell_analysis") SellAnalysis sellAnalysis) {

    static AiReportResponse from(Order order, List<Execution> executions) {
        ExecutionSummary execution = null;
        // ponytail: 더미 분석은 v1 문서 호환용. AI 분석 계약 확정 시 실제 저장 데이터로 교체한다.
        SellAnalysis sell = order.getSide() == Order.Side.SELL ? SellAnalysis.dummy(order) : null;
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
            if (order.getSide() == Order.Side.SELL && completePnl) {
                // 저장된 실현손익은 소수점 2자리이므로 역산 원가도 그 정밀도에 한정된다.
                var cost = amount.subtract(pnl);
                var buyPrice = cost.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
                var percent = cost.signum() > 0
                        ? pnl.multiply(BigDecimal.valueOf(100)).divide(cost, 4, RoundingMode.HALF_UP) : null;
                sell = new SellAnalysis(new TradeResult(buyPrice, 0, pnl, percent, BigDecimal.ZERO, false, false),
                        sell.buyDecision(), sell.holdingChanges(), sell.sellDecision(), sell.expectationVsOutcome());
            }
        }
        return new AiReportResponse("success", order.getId(),
                order.getSide().name().toLowerCase(Locale.ROOT),
                order.getStockCode(), "[더미] 종목명", "completed", seoul(order.getCreatedAt()),
                order.getReport().getReason(), execution, null, sell);
    }

    public record ExecutionSummary(
            @JsonProperty("executed_at") OffsetDateTime executedAt,
            @JsonProperty("execution_price") BigDecimal executionPrice,
            @JsonProperty("execution_quantity") long executionQuantity,
            @JsonProperty("trade_amount") BigDecimal tradeAmount) {}

    private static OffsetDateTime seoul(Instant time) {
        return time.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
    }

    public record SellAnalysis(
            @JsonProperty("trade_result") TradeResult tradeResult,
            @JsonProperty("buy_decision") Map<String, Object> buyDecision,
            @JsonProperty("holding_changes") List<Map<String, Object>> holdingChanges,
            @JsonProperty("sell_decision") String sellDecision,
            @JsonProperty("expectation_vs_outcome") Map<String, Object> expectationVsOutcome) {
        static SellAnalysis dummy(Order order) {
            return new SellAnalysis(
                    new TradeResult(BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, false),
                    Map.of("buy_report_id", 0, "summary", "[더미] 매수 당시 판단"),
                    List.of(Map.of("observed_at", seoul(order.getCreatedAt()), "summary", "[더미] 보유 중 변화")),
                    "[더미] 매도 판단",
                    Map.of("expected_return_min_percent", 0, "expected_return_max_percent", 0,
                            "summary", "[더미] 예상과 결과"));
        }
    }

    public record TradeResult(
            @JsonProperty("average_buy_price") BigDecimal averageBuyPrice,
            @JsonProperty("holding_days") int holdingDays,
            @JsonProperty("realized_pnl") BigDecimal realizedPnl,
            @JsonProperty("realized_return_percent") BigDecimal realizedReturnPercent,
            @JsonProperty("target_return_percent") BigDecimal targetReturnPercent,
            @JsonProperty("target_reached") boolean targetReached,
            @JsonProperty("stop_loss_triggered") boolean stopLossTriggered) {}
}
