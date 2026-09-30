package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.account.repository.AccountRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 지정가 전량 체결. 모든 자산 변경은 계좌 잠금 아래 한 트랜잭션으로 실행한다. */
@Service
public class OrderExecutionService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final AccountRepository accounts;
    private final OrderRepository orders;
    private final HoldingRepository holdings;
    private final ExecutionRepository executions;
    private final Clock clock;

    @Autowired
    public OrderExecutionService(AccountRepository accounts, OrderRepository orders,
            HoldingRepository holdings, ExecutionRepository executions) {
        this(accounts, orders, holdings, executions, Clock.systemUTC());
    }

    OrderExecutionService(AccountRepository accounts, OrderRepository orders,
            HoldingRepository holdings, ExecutionRepository executions, Clock clock) {
        this.accounts = accounts;
        this.orders = orders;
        this.holdings = holdings;
        this.executions = executions;
        this.clock = clock;
    }

    @Transactional
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
        long amount = Math.multiplyExact(currentPrice, quantity);
        var cost = BigDecimal.valueOf(amount);
        var holding = holdings.findByAccountIdAndStockCode(accountId, order.getStockCode());
        BigDecimal pnl = null;
        BigDecimal returnPercent = null;
        if (order.getSide() == Order.Side.BUY) {
            account.changeCash(-amount);
            if (holding.isPresent()) holding.get().add(quantity, cost);
            else holdings.save(new Holding(account, order.getStockCode(), quantity, cost));
        } else {
            var owned = holding.orElseThrow(() -> new IllegalStateException("매도 보유종목이 없습니다."));
            if (owned.getQuantity() < quantity) throw new IllegalStateException("매도 보유수량이 부족합니다.");
            if (owned.getTotalCost().signum() <= 0) throw new IllegalStateException("취득원가는 양수여야 합니다.");
            // 나눗셈을 마지막에 수행해 평균단가의 조기 반올림 오차를 피한다.
            var soldCost = owned.getTotalCost().multiply(BigDecimal.valueOf(quantity))
                    .divide(BigDecimal.valueOf(owned.getQuantity()), 16, RoundingMode.HALF_UP);
            pnl = cost.subtract(soldCost);
            returnPercent = pnl.multiply(BigDecimal.valueOf(100))
                    .divide(soldCost, 4, RoundingMode.HALF_UP);
            account.changeCash(amount);
            if (owned.getQuantity() == quantity) holdings.delete(owned);
            else owned.reduce(quantity, soldCost);
        }
        executions.save(new Execution(order, currentPrice, quantity, pnl, returnPercent, now));
        order.execute();
        return true;
    }
}