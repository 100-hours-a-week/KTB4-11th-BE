package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.time.Instant;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class OrderServiceTests {
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired OrderRepository orders;
    @Autowired HoldingRepository holdings;
    @Autowired OrderService service;
    private Account account;

    @BeforeEach
    void setup() {
        account = accounts.save(new Account(users.save(new User("주문테스트")), "AI 계좌", 1_000_000));
    }

    @Test
    void buyOrdersReserveAvailableCashWithoutChangingBalanceAndCancelReleasesIt() {
        Order first = service.reserveLimit(account.getId(), "005930", Order.Side.BUY,
                10, 70_000, "decision-1", "삼성전자 매수 판단");
        assertThat(first.getStatus()).isEqualTo(Order.Status.PENDING);
        assertThat(first.getSource()).isEqualTo(Order.Source.AI);
        assertThat(first.getReservedCash()).isEqualTo(700_000);
        assertThat(first.getDecisionSummary()).isEqualTo("삼성전자 매수 판단");
        assertThat(account.getCashBalance()).isEqualTo(1_000_000);
        assertThat(service.availableCash(account.getId())).isEqualTo(300_000);
        assertThatThrownBy(() -> service.reserveLimit(account.getId(), "000660", Order.Side.BUY,
                4, 100_000, null, null)).isInstanceOf(OrderException.class);
        assertThat(orders.count()).isEqualTo(1);
        Order second = service.reserveLimit(account.getId(), "000660", Order.Side.BUY,
                3, 100_000, null, null);
        assertThat(service.availableCash(account.getId())).isZero();
        service.cancel(account.getId(), first.getId());
        assertThat(first.getStatus()).isEqualTo(Order.Status.CANCELLED);
        assertThat(first.getReservedCash()).isZero();
        assertThat(first.getCancelledAt()).isNotNull();
        assertThat(service.availableCash(account.getId())).isEqualTo(700_000);
        assertThat(second.getReservedCash()).isEqualTo(300_000);
        assertThatThrownBy(() -> service.cancel(account.getId(), first.getId()))
                .isInstanceOf(OrderException.class);
    }

    @Test
    void sellOrdersReserveQuantityWithoutChangingHoldingAndAreAccountScoped() {
        holdings.save(new Holding(account, "005930", 10, new BigDecimal("650000.00")));
        Order first = service.reserveLimit(account.getId(), "005930", Order.Side.SELL,
                7, 70_000, null, null);
        assertThat(first.getReservedCash()).isZero();
        assertThat(service.sellableQuantity(account.getId(), "005930")).isEqualTo(3);
        assertThat(holdings.findByAccountIdAndStockCode(account.getId(), "005930")
                .orElseThrow().getQuantity()).isEqualTo(10);
        assertThatThrownBy(() -> service.reserveLimit(account.getId(), "005930", Order.Side.SELL,
                4, 70_000, null, null)).isInstanceOf(OrderException.class);
        var another = accounts.save(new Account(users.save(new User("다른사용자")), "AI 계좌", 1_000_000));
        assertThat(service.sellableQuantity(another.getId(), "005930")).isZero();
        assertThatThrownBy(() -> service.cancel(another.getId(), first.getId()))
                .isInstanceOf(OrderException.class);
        service.cancel(account.getId(), first.getId());
        assertThat(service.sellableQuantity(account.getId(), "005930")).isEqualTo(10);
    }

    @Test
    void previousDayPendingOrdersAreCancelledAndReservedCashIsReleased() {
        Order staleOrder = orders.save(Order.pendingLimit(account, "005930", Order.Side.BUY,
                2, 70_000, Order.Source.AI, null, null, Instant.parse("2000-01-01T00:00:00Z")));

        service.cancelExpiredPendingOrders();

        assertThat(staleOrder.getStatus()).isEqualTo(Order.Status.CANCELLED);
        assertThat(staleOrder.getReservedCash()).isZero();
        assertThat(staleOrder.getCancelledAt()).isNotNull();
        assertThat(service.availableCash(account.getId())).isEqualTo(1_000_000);
    }

    @Test
    void invalidRequestDoesNotCreateOrder() {
        assertThatThrownBy(() -> service.reserveLimit(account.getId(), "005930", Order.Side.BUY,
                0, 70_000, null, null)).isInstanceOf(OrderException.class);
        assertThatThrownBy(() -> service.reserveLimit(account.getId(), "005930", Order.Side.BUY,
                Long.MAX_VALUE, 70_000, null, null)).isInstanceOf(OrderException.class);
        assertThatThrownBy(() -> service.reserveLimit(account.getId(), "A", Order.Side.BUY,
                1, 70_000, null, null)).isInstanceOf(OrderException.class);
        assertThat(orders.count()).isZero();
    }
}
