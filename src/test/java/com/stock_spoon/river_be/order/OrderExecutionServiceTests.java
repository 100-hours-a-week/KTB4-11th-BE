package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class OrderExecutionServiceTests {
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired OrderRepository orders;
    @Autowired HoldingRepository holdings;
    @Autowired ExecutionRepository executions;
    private final Instant now = Instant.parse("2026-10-01T01:00:00Z");

    private OrderExecutionService service(Instant time) {
        return new OrderExecutionService(accounts, orders, holdings, executions, Clock.fixed(time, ZoneOffset.UTC));
    }
    private Account account() {
        return accounts.save(new Account(users.save(new User("체결검증")), "AI 계좌", 1000000));
    }
    private Order pending(Account account, Order.Side side, long quantity, long limit) {
        return orders.save(Order.pendingLimit(account, "005930", side, quantity, limit, Order.Source.AI, null, null, now));
    }

    @Test
    void buyUsesObservedPriceAndCannotExecuteTwice() {
        var account = account();
        var order = pending(account, Order.Side.BUY, 2, 70000);
        assertThat(service(now).executeLimit(account.getId(), order.getId(), 70100)).isFalse();
        assertThat(service(now).executeLimit(account.getId(), order.getId(), 69000)).isTrue();
        assertThat(service(now).executeLimit(account.getId(), order.getId(), 68000)).isFalse();
        assertThat(account.getCashBalance()).isEqualTo(862000);
        assertThat(order.getStatus()).isEqualTo(Order.Status.EXECUTED);
        assertThat(order.getReservedCash()).isZero();
        var holding = holdings.findByAccountIdAndStockCode(account.getId(), "005930").orElseThrow();
        assertThat(holding.getQuantity()).isEqualTo(2);
        assertThat(holding.getTotalCost()).isEqualByComparingTo("138000");
        assertThat(executions.count()).isEqualTo(1);
    }

    @Test
    void partialSellUsesAverageCostAndFullSellRemovesHolding() {
        var account = account();
        holdings.save(new Holding(account, "005930", 3, new BigDecimal("200000.00")));
        var first = pending(account, Order.Side.SELL, 1, 70000);
        assertThat(service(now).executeLimit(account.getId(), first.getId(), 69000)).isFalse();
        assertThat(service(now).executeLimit(account.getId(), first.getId(), 70000)).isTrue();
        var remaining = holdings.findByAccountIdAndStockCode(account.getId(), "005930").orElseThrow();
        assertThat(remaining.getQuantity()).isEqualTo(2);
        assertThat(remaining.getTotalCost()).isEqualByComparingTo("133333.33");
        var execution = executions.findAll().getFirst();
        assertThat(execution.getRealizedPnl()).isEqualByComparingTo("3333.33");
        assertThat(execution.getRealizedReturnPercent()).isEqualByComparingTo("5.0000");
        var second = pending(account, Order.Side.SELL, 2, 70000);
        assertThat(service(now).executeLimit(account.getId(), second.getId(), 70000)).isTrue();
        assertThat(holdings.findByAccountIdAndStockCode(account.getId(), "005930")).isEmpty();
        assertThat(account.getCashBalance()).isEqualTo(1210000);
    }

    @Test
    void marketCloseCancelsInsteadOfExecuting() {
        var account = account();
        var order = pending(account, Order.Side.BUY, 2, 70000);
        assertThat(service(Instant.parse("2026-10-01T06:30:00Z"))
                .executeLimit(account.getId(), order.getId(), 69000)).isFalse();
        assertThat(order.getStatus()).isEqualTo(Order.Status.CANCELLED);
        assertThat(account.getCashBalance()).isEqualTo(1000000);
        assertThat(executions.count()).isZero();
    }
}