package com.stock_spoon.river_be.order.service;

import com.stock_spoon.river_be.order.dto.OrderHistoryResponse;
import com.stock_spoon.river_be.execution.entity.Execution;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.execution.repository.ExecutionRepository;
import com.stock_spoon.river_be.order.repository.OrderRepository;

import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.service.CompetitionHistoryAccess;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@Service
public class OrderHistoryService {
    private static final Logger log = LoggerFactory.getLogger(OrderHistoryService.class);
    private final AccountRepository accounts;
    private final OrderRepository orders;
    private final ExecutionRepository executions;
    private final KiwoomMarketClient market;
    private final TransactionTemplate read;
    private final CompetitionHistoryAccess competitionAccess;

    public OrderHistoryService(AccountRepository accounts, OrderRepository orders,
            ExecutionRepository executions, KiwoomMarketClient market, PlatformTransactionManager transactions,
            CompetitionHistoryAccess competitionAccess) {
        this.accounts = accounts;
        this.orders = orders;
        this.executions = executions;
        this.market = market;
        this.competitionAccess = competitionAccess;
        read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
    }

    public OrderHistoryResponse list(long userId, long accountId, String limit, String page, String orderSide,
            String orderStatus, String stockCode) {
        long started = System.nanoTime();
        if (stockCode != null && stockCode.isBlank()) throw invalidQuery();
        Order.Status selectedStatus = parseStatus(orderStatus);
        Integer pageNumber = page == null ? null : parseNumber(page, 0);
        int count = limit == null ? (pageNumber == null ? Integer.MAX_VALUE : 10) : parseNumber(limit, 1);
        if (pageNumber != null && count > 100) throw invalidQuery();
        Order.Side side = null;
        if (orderSide != null) {
            side = switch (orderSide) {
                case "buy" -> Order.Side.BUY;
                case "sell" -> Order.Side.SELL;
                default -> throw invalidQuery();
            };
        }
        final Order.Side selectedSide = side;
        log.debug("주문내역 조회 시작 accountId={} limit={} requestedStatus={}", accountId, count, selectedStatus);
        Snapshot snapshot = read.execute(status -> {
            var account = accounts.findByIdAndUserIdAndActiveTrue(accountId, userId).orElseThrow(() -> {
                log.warn("event=order_history_rejected stage=account_lookup 주문내역 계좌 접근 실패 accountId={} code=ACCOUNT_NOT_FOUND", accountId);
                return new OrderException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "계좌를 찾을 수 없습니다.");
            });
            competitionAccess.check(userId, accountId, false);
            var source = account.isAiManaged() ? Order.Source.AI : Order.Source.USER;
            long total = pageNumber == null ? 0
                    : orders.countHistory(accountId, selectedStatus, source, selectedSide, stockCode);
            long offset = pageNumber == null ? 0 : (long) pageNumber * count;
            // JPA의 offset은 int 범위다. 전체 개수 밖 요청은 DB 페이지 조회 없이 빈 결과로 반환한다.
            if (pageNumber != null && offset >= total) return new Snapshot(List.of(), total);
            if (offset > Integer.MAX_VALUE) throw invalidQuery();
            var candidates = orders.findHistory(accountId, selectedStatus, source, selectedSide, stockCode,
                    pageNumber == null ? Pageable.unpaged() : PageRequest.of(pageNumber, count));
            if (candidates.isEmpty()) return new Snapshot(List.of(), total);
            var byOrder = executions.findForOrders(candidates.stream().map(Order::getId).toList()).stream()
                    .collect(Collectors.groupingBy(Execution::getOrderId));
            var rows = candidates.stream().map(o -> {
                var fills = byOrder.getOrDefault(o.getId(), List.of());
                if (o.getStatus() == Order.Status.EXECUTED && fills.isEmpty()) {
                    log.error("event=order_history_failed stage=execution_data_validation code=INVALID_ORDER_DATA 주문내역 체결 데이터 오류 accountId={} orderId={} count={}", accountId, o.getId(), fills.size());
                    throw new OrderException(HttpStatus.INTERNAL_SERVER_ERROR, "INVALID_ORDER_DATA", "주문 체결 데이터를 확인해주세요.");
                }
                return snapshot(o, fills);
            }).toList();
            // page 생략 시 전체 후보 검증 후 기존 limit을 적용한다.
            return new Snapshot(pageNumber == null ? rows.stream().limit(count).toList() : rows, total);
        });
        log.debug("주문내역 DB 읽기 완료 accountId={} selected={}", accountId, snapshot.rows().size());
        var names = new HashMap<String, String>();
        var result = snapshot.rows().stream().map(row -> row.withStockName(names.computeIfAbsent(row.stockCode(), code -> {
            try {
                return market.stockDetails(code).stockName();
            } catch (IllegalStateException error) {
                log.error("event=order_history_failed stage=stock_details_lookup code=ORDER_DATA_UNAVAILABLE 주문내역 종목 조회 실패 accountId={} stockCode={} causeType={}",
                        accountId, code, error.getClass().getSimpleName());
                throw new OrderException(HttpStatus.SERVICE_UNAVAILABLE, "ORDER_DATA_UNAVAILABLE", "주문 종목 정보를 조회할 수 없습니다.");
            }
        }))).toList();
        log.info("주문내역 조회 완료 accountId={} count={} elapsedMs={}", accountId, result.size(),
                (System.nanoTime() - started) / 1_000_000);
        var pagination = pageNumber == null ? null : new OrderHistoryResponse.Pagination(pageNumber, count,
                snapshot.total(), snapshot.total() / count + (snapshot.total() % count == 0 ? 0 : 1),
                ((long) pageNumber + 1) * count < snapshot.total());
        return new OrderHistoryResponse(accountId, result, pagination);
    }

    private OrderHistoryResponse.Item snapshot(Order order, List<Execution> fills) {
        var items = fills.stream().map(fill -> new OrderHistoryResponse.ExecutionItem(fill.getId(), fill.getPrice(),
                fill.getQuantity(), fill.getRealizedPnl(), fill.getRealizedReturnPercent(), date(fill.getCreatedAt()))).toList();
        var summary = fills.isEmpty() ? null : summarize(order, fills);
        var report = order.getReport();
        String reason = report == null ? null : report.getReason();
        return new OrderHistoryResponse.Item(order.getId(), order.getStockCode(), null, order.getSource().name(),
                order.getSide().name().toLowerCase(Locale.ROOT), order.getType().name().toLowerCase(Locale.ROOT),
                order.getStatus().name().toLowerCase(Locale.ROOT), order.getQuantity(), order.getLimitPrice(), order.getReservedCash(),
                date(order.getCreatedAt()), date(order.getCancelledAt()), new OrderHistoryResponse.Reason(reason),
                items, summary, order.getStatus() == Order.Status.PENDING);
    }

    private OrderHistoryResponse.ExecutionSummary summarize(Order order, List<Execution> fills) {
        long quantity = 0;
        long amount = 0;
        Instant last = fills.getFirst().getCreatedAt();
        BigDecimal pnl = BigDecimal.ZERO;
        boolean completePnl = true;
        for (var fill : fills) {
            quantity = Math.addExact(quantity, fill.getQuantity());
            amount = Math.addExact(amount, Math.multiplyExact(fill.getPrice(), fill.getQuantity()));
            if (fill.getCreatedAt().isAfter(last)) last = fill.getCreatedAt();
            if (fill.getRealizedPnl() == null) completePnl = false;
            else pnl = pnl.add(fill.getRealizedPnl());
        }
        BigDecimal realized = order.getSide() == Order.Side.SELL && completePnl ? pnl : null;
        BigDecimal rate = null;
        if (fills.size() == 1) rate = fills.getFirst().getRealizedReturnPercent();
        else if (realized != null) {
            // AI 리포트와 동일하게 매도금액 - 저장 손익으로 전체 취득원가를 역산한다.
            var cost = BigDecimal.valueOf(amount).subtract(realized);
            if (cost.signum() > 0) rate = realized.multiply(BigDecimal.valueOf(100))
                    .divide(cost, 4, RoundingMode.HALF_UP);
        }
        var average = BigDecimal.valueOf(amount).divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
        return new OrderHistoryResponse.ExecutionSummary(quantity, average, amount, date(last), realized, rate);
    }

    private static OffsetDateTime date(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.ofHours(9));
    }

    private Order.Status parseStatus(String value) {
        if (value == null) return null;
        return switch (value) {
            case "pending" -> Order.Status.PENDING;
            case "executed" -> Order.Status.EXECUTED;
            case "cancelled" -> Order.Status.CANCELLED;
            default -> throw invalidQuery();
        };
    }

    private int parseNumber(String value, int minimum) {
        try {
            if (!value.matches("[0-9]+")) throw invalidQuery();
            int parsed = Integer.parseInt(value);
            if (parsed < minimum) throw invalidQuery();
            return parsed;
        } catch (NumberFormatException error) {
            throw invalidQuery();
        }
    }

    private OrderException invalidQuery() {
        log.warn("event=order_history_rejected stage=query_validation 주문내역 조회 조건 오류 code=INVALID_ORDER_QUERY");
        return new OrderException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_QUERY", "page, limit, order_side, order_status, stock_code 값을 확인해주세요.");
    }

    private record Snapshot(List<OrderHistoryResponse.Item> rows, long total) {}
}
