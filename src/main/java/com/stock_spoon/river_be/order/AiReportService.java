package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.account.repository.AccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiReportService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiReportService.class);
    private final AccountRepository accounts;
    private final OrderRepository orders;
    private final ExecutionRepository executions;

    public AiReportService(AccountRepository accounts, OrderRepository orders, ExecutionRepository executions) {
        this.accounts = accounts;
        this.orders = orders;
        this.executions = executions;
    }

    @Transactional(readOnly = true)
    public AiReportResponse get(long userId, long accountId, long orderId) {
        accounts.findByIdAndUserIdAndActiveTrue(accountId, userId).orElseThrow(() ->
                new OrderException(HttpStatus.FORBIDDEN, "FORBIDDEN_ACCOUNT", "이 계좌를 조회할 권한이 없습니다."));
        var order = orders.findByIdAndAccountId(orderId, accountId).orElseThrow(AiReportService::notFound);
        if (order.getSource() != Order.Source.AI || order.getReport() == null
                || order.getStatus() != Order.Status.EXECUTED) throw notFound();
        var fills = executions.findForOrder(orderId);
        if (fills.isEmpty()) throw notFound();
        Integer holdingDays = null;
        if (order.getSide() == Order.Side.SELL && !fills.isEmpty()) {
            var last = fills.stream().max(java.util.Comparator.comparing(Execution::getCreatedAt)
                    .thenComparing(Execution::getId)).orElseThrow();
            holdingDays = holdingDays(executions.findHoldingTrades(accountId, order.getStockCode(),
                    last.getCreatedAt(), last.getId()), last.getCreatedAt());
        }
        var result = AiReportResponse.from(order, fills, holdingDays);
        log.info("event=ai_report_completed orderId={} executionCount={}", orderId, fills.size());
        return result;
    }

    private static Integer holdingDays(java.util.List<ExecutionRepository.HoldingTrade> trades,
            java.time.Instant soldAt) {
        long quantity = 0;
        java.time.Instant started = null;
        try {
            for (var trade : trades) {
                if (trade.getSide() == Order.Side.BUY) {
                    if (quantity == 0) started = trade.getCreatedAt();
                    quantity = Math.addExact(quantity, trade.getQuantity());
                } else {
                    quantity = Math.subtractExact(quantity, trade.getQuantity());
                    if (quantity < 0) return null;
                }
            }
            if (started == null) return null;
            var seoul = java.time.ZoneId.of("Asia/Seoul");
            return Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(
                    started.atZone(seoul).toLocalDate(), soldAt.atZone(seoul).toLocalDate()));
        } catch (ArithmeticException error) {
            return null;
        }
    }

    private static OrderException notFound() {
        return new OrderException(HttpStatus.NOT_FOUND, "AI_REPORT_NOT_FOUND", "AI 매매 리포트를 찾을 수 없습니다.");
    }
}
