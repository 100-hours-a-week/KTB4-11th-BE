package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.OrderBook;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.QuoteLevel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 시장가·지정가 전량 체결. 모든 자산 변경은 계좌 잠금 아래 한 트랜잭션으로 실행한다. */
@Service
public class OrderExecutionService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final AccountRepository accounts;
    private final OrderRepository orders;
    private final HoldingRepository holdings;
    private final ExecutionRepository executions;
    private final Clock clock;
    private final com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream stream;

    @Autowired
    public OrderExecutionService(AccountRepository accounts, OrderRepository orders,
            HoldingRepository holdings, ExecutionRepository executions,
            com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream stream) {
        this(accounts, orders, holdings, executions, Clock.systemUTC(), stream);
    }

    OrderExecutionService(AccountRepository accounts, OrderRepository orders,
            HoldingRepository holdings, ExecutionRepository executions, Clock clock) {
        this(accounts, orders, holdings, executions, clock, null);
    }

    OrderExecutionService(AccountRepository accounts, OrderRepository orders,
            HoldingRepository holdings, ExecutionRepository executions, Clock clock,
            com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream stream) {
        this.stream = stream;
        this.accounts = accounts;
        this.orders = orders;
        this.holdings = holdings;
        this.executions = executions;
        this.clock = clock;
    }

    @Transactional
    public Order executeMarketBuy(long userId, long accountId, String stockCode, long quantity,
            String reason, OrderBook book) {
        return executeMarket(userId, accountId, stockCode, Order.Side.BUY, quantity, reason, book);
    }

    @Transactional
    public Order executeMarketSell(long userId, long accountId, String stockCode, long quantity,
            String reason, OrderBook book) {
        return executeMarket(userId, accountId, stockCode, Order.Side.SELL, quantity, reason, book);
    }

    @Transactional
    public Order executeMarket(long userId, long accountId, OrderCreateRequest request, OrderBook book) {
        return executeMarket(userId, accountId, request.stockCode(),
                Order.Side.valueOf(request.orderSide().toUpperCase(java.util.Locale.ROOT)),
                request.quantity(), request.reason(), book, request.stockName(), request.reasoning(),
                request.holdingWeightLimitPercent(), request.isLowerTriggered());
    }

    /** 공개 메서드의 트랜잭션 안에서 주문·체결·자산을 전량 반영한다. */
    private Order executeMarket(long userId, long accountId, String stockCode, Order.Side side,
            long quantity, String reason, OrderBook book) {
        return executeMarket(userId, accountId, stockCode, side, quantity, reason, book, null, java.util.List.of(), null, null);
    }

    private Order executeMarket(long userId, long accountId, String stockCode, Order.Side side,
            long quantity, String reason, OrderBook book, String stockName, java.util.List<ReportReasoning> reasoning,
            Double holdingWeightLimitPercent, Boolean isLowerTriggered) {
        if (stockCode == null || !stockCode.matches("[0-9]{6}") || quantity <= 0
                || reason == null || reason.isBlank() || reason.length() > 100000) {
            throw new OrderException("주문 입력값을 확인하세요.");
        }
        var account = accounts.findLockedById(accountId)
                .orElseThrow(() -> new OrderException("계좌를 찾을 수 없습니다."));
        if (!account.belongsTo(userId)) {
            throw new OrderException(HttpStatus.FORBIDDEN,
                    "FORBIDDEN_ACCOUNT", "이 계좌에 주문할 권한이 없습니다.");
        }
        if (!account.isActive() || !account.isAiManaged()) {
            throw new OrderException("운용 가능한 자동매매 계좌가 아닙니다.");
        }
        var now = clock.instant();
        var local = now.atZone(SEOUL);
        if (local.toLocalTime().isBefore(LocalTime.of(9, 0))
                || !local.toLocalTime().isBefore(LocalTime.of(15, 30))) {
            throw new OrderException(HttpStatus.CONFLICT,
                    "ORDER_WINDOW_CLOSED", "주문 생성은 한국 시간 09:00부터 15:30 전까지 가능합니다.");
        }
        if (book == null || !stockCode.equals(book.stockCode()) || book.receivedAt() == null
                || book.quoteTime() == null || book.receivedAt().isAfter(now)
                || book.receivedAt().isBefore(now.minusSeconds(5))) {
            throw unavailableBook();
        }
        var quotedAt = local.toLocalDate().atTime(book.quoteTime()).atZone(SEOUL).toInstant();
        if (!book.receivedAt().atZone(SEOUL).toLocalDate().equals(local.toLocalDate())
                || quotedAt.isBefore(now.minusSeconds(5)) || quotedAt.isAfter(now.plusSeconds(1))) {
            throw unavailableBook();
        }
        var levels = side == Order.Side.BUY ? book.asks() : book.bids();
        var priority = Comparator.comparing(QuoteLevel::price);
        if (side == Order.Side.SELL) priority = priority.reversed();
        var fills = new ArrayList<QuoteLevel>();
        long remaining = quantity;
        long amount = 0;
        try {
            for (var level : levels) {
                if (level == null || level.price() == null || level.price().signum() < 0
                        || level.quantity() < 0 || (level.quantity() > 0 && level.price().signum() == 0)) {
                    throw unavailableBook();
                }
                try {
                    level.price().longValueExact();
                } catch (ArithmeticException error) {
                    throw unavailableBook();
                }
            }
            for (var level : levels.stream().sorted(priority).toList()) {
                if (remaining == 0) break;
                long filled = Math.min(remaining, level.quantity());
                if (filled == 0) continue;
                amount = Math.addExact(amount, Math.multiplyExact(level.price().longValueExact(), filled));
                fills.add(new QuoteLevel(level.price(), filled));
                remaining -= filled;
            }
        } catch (ArithmeticException error) {
            throw new OrderException("호가 또는 주문금액이 허용 범위를 초과합니다.");
        }
        if (remaining != 0) throw new OrderException("시장가 주문을 전량 체결할 상대 호가 잔량이 부족합니다.");
        var owned = holdings.findByAccountIdAndStockCode(accountId, stockCode).orElse(null);
        // 체결별 원가 계산은 보유정보 갱신 전의 동일한 수량·총 취득원가를 사용한다.
        long originalQuantity = owned == null ? 0 : owned.getQuantity();
        BigDecimal originalCost = owned == null ? null : owned.getTotalCost();
        if (side == Order.Side.BUY) {
            long available = Math.subtractExact(account.getCashBalance(),
                    orders.reservedCash(accountId, Order.Status.PENDING));
            if (available < amount) throw new OrderException("주문 가능 현금이 부족합니다.");
        } else {
            long pending = orders.pendingSellQuantity(accountId, stockCode, Order.Side.SELL, Order.Status.PENDING);
            if (Math.subtractExact(originalQuantity, pending) < quantity) {
                throw new OrderException("매도 가능 수량이 부족합니다.");
            }
            if (originalCost == null || originalCost.signum() <= 0) {
                throw new IllegalStateException("취득원가는 양수여야 합니다.");
            }
        }
        long cashDelta = side == Order.Side.BUY ? -amount : amount;
        try {
            Math.addExact(account.getCashBalance(), cashDelta);
        } catch (ArithmeticException error) {
            throw new OrderException("현금 잔액이 허용 범위를 초과합니다.");
        }
        var order = orders.save(Order.pendingMarket(account, stockCode, side, quantity, reason, stockName, reasoning, holdingWeightLimitPercent, isLowerTriggered, now));
        account.changeCash(cashDelta);
        var cost = BigDecimal.valueOf(amount);
        if (side == Order.Side.BUY) {
            if (owned != null) owned.add(quantity, cost);
            else holdings.save(new Holding(account, stockCode, quantity, cost));
        } else if (originalQuantity == quantity) {
            holdings.delete(owned);
        } else {
            owned.reduce(quantity, soldCost(originalCost, originalQuantity, quantity));
        }
        for (var fill : fills) {
            BigDecimal pnl = null;
            BigDecimal returnPercent = null;
            if (side == Order.Side.SELL) {
                var fillCost = soldCost(originalCost, originalQuantity, fill.quantity());
                pnl = fill.price().multiply(BigDecimal.valueOf(fill.quantity())).subtract(fillCost);
                returnPercent = pnl.multiply(BigDecimal.valueOf(100)).divide(fillCost, 4, RoundingMode.HALF_UP);
            }
            executions.save(new Execution(order, fill.price().longValueExact(), fill.quantity(), pnl, returnPercent, now));
        }
        if (order.getSide() == Order.Side.BUY) recordBuyWeight(order, account, amount, quantity);
        order.execute();
        return order;
    }

    private void recordBuyWeight(Order order,
            com.stock_spoon.river_be.account.entity.Account account, long amount, long quantity) {
        if (order.getReport() == null) return;
        var fillPrice = BigDecimal.valueOf(amount).divide(BigDecimal.valueOf(quantity), java.math.MathContext.DECIMAL128);
        BigDecimal total = BigDecimal.valueOf(account.getCashBalance());
        BigDecimal stockValue = null;
        for (var owned : holdings.findAllByAccountIds(java.util.List.of(account.getId()))) {
            BigDecimal price;
            if (owned.getStockCode().equals(order.getStockCode())) {
                price = fillPrice;
            } else {
                var quote = stream == null ? null : stream.latest(owned.getStockCode()).orElse(null);
                if (quote == null || quote.currentPrice() == null || quote.currentPrice().signum() <= 0) return;
                price = quote.currentPrice();
            }
            var value = price.multiply(BigDecimal.valueOf(owned.getQuantity()));
            total = total.add(value);
            if (owned.getStockCode().equals(order.getStockCode())) stockValue = value;
        }
        if (stockValue != null && total.signum() > 0) {
            order.getReport().recordHoldingWeight(stockValue.multiply(BigDecimal.valueOf(100))
                    .divide(total, 4, RoundingMode.HALF_UP).doubleValue());
        }
    }

    private static BigDecimal soldCost(BigDecimal totalCost, long heldQuantity, long soldQuantity) {
        return totalCost.multiply(BigDecimal.valueOf(soldQuantity))
                .divide(BigDecimal.valueOf(heldQuantity), 16, RoundingMode.HALF_UP);
    }

    private static OrderException unavailableBook() {
        return new OrderException(HttpStatus.SERVICE_UNAVAILABLE,
                "MARKET_DATA_UNAVAILABLE", "사용 가능한 최신 호가를 확인할 수 없습니다.");
    }

    @Transactional
    // [체결 트랜잭션] 계좌 잠금 → 주문 재확인 → 조건 판단 → 현금·보유·체결·상태 변경.
    // 런타임 예외 시 이 트랜잭션의 변경을 함께 되돌린다. 앞서 커밋된 예약은 별도다.
    public boolean executeLimit(long accountId, long orderId, long currentPrice) {
        if (currentPrice <= 0) throw new IllegalArgumentException("현재가는 양수여야 합니다.");
        var account = accounts.findLockedById(accountId).orElseThrow();
        var order = orders.findByIdAndAccountId(orderId, accountId).orElseThrow();
        if (order.getStatus() != Order.Status.PENDING || order.getType() != Order.Type.LIMIT) return false;
        var now = clock.instant();
        var local = now.atZone(SEOUL);
        if (order.getCreatedAt().atZone(SEOUL).toLocalDate().isBefore(local.toLocalDate())
                || !local.toLocalTime().isBefore(LocalTime.of(15, 30))) {
            order.cancel(now);
            return false;
        }
        if (local.toLocalTime().isBefore(LocalTime.of(9, 0))) return false;
        if (order.getSide() == Order.Side.BUY ? currentPrice > order.getLimitPrice()
                : currentPrice < order.getLimitPrice()) return false;
        long quantity = order.getQuantity();
        // 체결 금액은 지정가가 아니라 조건을 만족한 현재가 × 주문 수량이다.
        long amount = Math.multiplyExact(currentPrice, quantity);
        var cost = BigDecimal.valueOf(amount);
        var holding = holdings.findByAccountIdAndStockCode(accountId, order.getStockCode());
        BigDecimal pnl = null;
        BigDecimal returnPercent = null;
        if (order.getSide() == Order.Side.BUY) {
            // 조회한 Account의 현금을 변경한다. 관리 상태 Entity이므로 별도 save 없이 변경 감지로 반영한다.
            account.changeCash(-amount);
            if (holding.isPresent()) holding.get().add(quantity, cost);
            else holdings.save(new Holding(account, order.getStockCode(), quantity, cost));
        } else {
            var owned = holding.orElseThrow(() -> new IllegalStateException("매도 보유종목이 없습니다."));
            if (owned.getQuantity() < quantity) throw new IllegalStateException("매도 보유수량이 부족합니다.");
            if (owned.getTotalCost().signum() <= 0) throw new IllegalStateException("취득원가는 양수여야 합니다.");
            // 나눗셈을 마지막에 수행해 평균단가의 조기 반올림 오차를 피한다.
            var soldCost = soldCost(owned.getTotalCost(), owned.getQuantity(), quantity);
            pnl = cost.subtract(soldCost);
            returnPercent = pnl.multiply(BigDecimal.valueOf(100))
                    .divide(soldCost, 4, RoundingMode.HALF_UP);
            account.changeCash(amount);
            if (owned.getQuantity() == quantity) holdings.delete(owned);
            else owned.reduce(quantity, soldCost);
        }
        // 새 Execution은 얼마에 몇 주 체결됐는지 보존한다.
        // 기존 자산·주문 수정과 새 체결 기록 저장이 같은 트랜잭션에 속한다.
        executions.save(new Execution(order, currentPrice, quantity, pnl, returnPercent, now));
        if (order.getSide() == Order.Side.BUY) recordBuyWeight(order, account, amount, quantity);
        order.execute();
        return true;
    }
}