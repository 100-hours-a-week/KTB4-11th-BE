package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderHistoryService {
    private static final Logger log = LoggerFactory.getLogger(OrderHistoryService.class);
    private final AccountRepository accounts;
    private final OrderRepository orders;
    private final ExecutionRepository executions;
    private final KiwoomMarketClient market;
    private final TransactionTemplate read;

    public OrderHistoryService(AccountRepository accounts, OrderRepository orders,
            ExecutionRepository executions, KiwoomMarketClient market, PlatformTransactionManager transactions) {
        this.accounts = accounts;
        this.orders = orders;
        this.executions = executions;
        this.market = market;
        read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
    }

    public OrderHistoryResponse list(long userId, long accountId, String limit) {
        long started = System.nanoTime();
        int count = parseLimit(limit);
        log.debug("주문내역 조회 시작 accountId={} limit={}", accountId, count);
        List<OrderHistoryResponse.Item> rows = read.execute(status -> {
            if (accounts.findByIdAndUserIdAndActiveTrue(accountId, userId).isEmpty()) {
                log.warn("주문내역 계좌 접근 실패 accountId={} code=ACCOUNT_NOT_FOUND", accountId);
                throw new OrderException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "계좌를 찾을 수 없습니다.");
            }
            var candidates = orders.findAllByAccountIdAndStatus(accountId, Order.Status.EXECUTED).stream()
                    .filter(o -> o.getSource() == Order.Source.AI).toList();
            if (candidates.isEmpty()) return List.of();
            var byOrder = executions.findForOrders(candidates.stream().map(Order::getId).toList()).stream()
                    .collect(Collectors.groupingBy(Execution::getOrderId));
            return candidates.stream().map(o -> {
                var fills = byOrder.getOrDefault(o.getId(), List.of());
                if (fills.size() != 1) {
                    log.error("주문내역 체결 데이터 오류 accountId={} orderId={} count={}", accountId, o.getId(), fills.size());
                    throw new OrderException(HttpStatus.INTERNAL_SERVER_ERROR, "INVALID_ORDER_DATA", "주문 체결 데이터를 확인해주세요.");
                }
                return snapshot(o, fills.getFirst());
            }).toList();
        });
        log.debug("주문내역 DB 읽기 완료 accountId={} candidates={}", accountId, rows.size());
        // ponytail: 전체 이력을 메모리에서 정렬한다. 이력이 커지면 체결시각 기준 DB 페이지 조회로 전환한다.
        var selected = rows.stream().sorted(Comparator
                .comparing((OrderHistoryResponse.Item row) -> row.executionSummary().executedAt()).reversed()
                .thenComparing(Comparator.comparingLong(OrderHistoryResponse.Item::orderId).reversed()))
                .limit(count).toList();
        var names = new HashMap<String, String>();
        var result = selected.stream().map(row -> row.withStockName(names.computeIfAbsent(row.stockCode(), code -> {
            try {
                return market.stockDetails(code).stockName();
            } catch (IllegalStateException error) {
                log.error("주문내역 종목 조회 실패 accountId={} stockCode={} causeType={}",
                        accountId, code, error.getClass().getSimpleName());
                throw new OrderException(HttpStatus.SERVICE_UNAVAILABLE, "ORDER_DATA_UNAVAILABLE", "주문 종목 정보를 조회할 수 없습니다.");
            }
        }))).toList();
        log.info("주문내역 조회 완료 accountId={} count={} elapsedMs={}", accountId, result.size(),
                (System.nanoTime() - started) / 1_000_000);
        return new OrderHistoryResponse(accountId, result);
    }

    private OrderHistoryResponse.Item snapshot(Order order, Execution fill) {
        var execution = new OrderHistoryResponse.ExecutionItem(fill.getId(), fill.getPrice(), fill.getQuantity(),
                fill.getRealizedPnl(), fill.getRealizedReturnPercent(), date(fill.getCreatedAt()));
        var summary = new OrderHistoryResponse.ExecutionSummary(fill.getQuantity(), fill.getPrice(),
                Math.multiplyExact(fill.getPrice(), fill.getQuantity()), date(fill.getCreatedAt()),
                fill.getRealizedPnl(), fill.getRealizedReturnPercent());
        String reason = order.getSide() == Order.Side.BUY
                ? "[더미] AI 판단에 따라 매수했어요." : "[더미] AI 판단에 따라 매도했어요.";
        return new OrderHistoryResponse.Item(order.getId(), order.getStockCode(), null, order.getSource().name(),
                order.getSide().name().toLowerCase(Locale.ROOT), order.getType().name().toLowerCase(Locale.ROOT),
                "executed", order.getQuantity(), order.getLimitPrice(), order.getReservedCash(),
                date(order.getCreatedAt()), date(order.getCancelledAt()), new OrderHistoryResponse.Reason(reason),
                List.of(execution), summary, false);
    }

    private static OffsetDateTime date(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.ofHours(9));
    }

    private int parseLimit(String value) {
        if (value == null) return Integer.MAX_VALUE;
        try {
            if (!value.matches("[0-9]+")) throw invalidQuery();
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) throw invalidQuery();
            return parsed;
        } catch (NumberFormatException error) {
            throw invalidQuery();
        }
    }

    private OrderException invalidQuery() {
        log.warn("주문내역 조회 조건 오류 code=INVALID_ORDER_QUERY");
        return new OrderException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_QUERY", "limit 값을 확인해주세요.");
    }
}
