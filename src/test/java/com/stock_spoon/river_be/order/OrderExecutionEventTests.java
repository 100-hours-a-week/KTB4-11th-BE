package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:execution-events;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Import(OrderExecutionEventTests.FixedTime.class)
class OrderExecutionEventTests {
    private static final Instant NOW = Instant.parse("2026-10-01T01:00:00Z");
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired OrderRepository orders;
    @Autowired HoldingRepository holdings;
    @Autowired ExecutionRepository executions;
    @Autowired ApplicationEventPublisher events;
    @Autowired OrderExecutionListener listener;
    @MockitoBean KiwoomStockStream stream;
    @MockitoBean KiwoomMarketClient market;
    @MockitoBean OrderSubscriptionService subscriptions;

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary
        OrderExecutionService fixedExecution(AccountRepository accounts, OrderRepository orders,
                HoldingRepository holdings, ExecutionRepository executions) {
            return new OrderExecutionService(accounts, orders, holdings, executions,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }

    @AfterEach
    void cleanup() {
        executions.deleteAll();
        orders.deleteAll();
        holdings.deleteAll();
        accounts.deleteAll();
        users.deleteAll();
    }

    private Account account(String name) {
        return accounts.save(new Account(users.save(new User(name)), name, 1000000));
    }

    private Order order(Account account) {
        return orders.save(Order.pendingLimit(account, "005930", Order.Side.BUY, 1, 70000,
                Order.Source.AI, null, null, NOW.minusSeconds(1)));
    }

    private KiwoomStockStream.StockPrice price() {
        return new KiwoomStockStream.StockPrice("005930", new BigDecimal("69000"),
                BigDecimal.ZERO, BigDecimal.ZERO, LocalTime.of(10, 0), NOW);
    }

    @Test
    void databaseFailureRollsBackCashWhileAnotherOrderCommitsAndRepeatEventDoesNotDuplicate() {
        var failedAccount = account("실패 계좌");
        var goodAccount = account("정상 계좌");
        var failedOrder = order(failedAccount);
        var goodOrder = order(goodAccount);
        // 현금 차감 뒤 보유수량 합산에서 오버플로를 발생시켜 실제 트랜잭션 롤백을 확인한다.
        holdings.save(new Holding(failedAccount, "005930", Long.MAX_VALUE, BigDecimal.ONE));
        events.publishEvent(price());
        events.publishEvent(price());

        assertThat(accounts.findById(failedAccount.getId()).orElseThrow().getCashBalance()).isEqualTo(1000000);
        assertThat(orders.findById(failedOrder.getId()).orElseThrow().getStatus()).isEqualTo(Order.Status.PENDING);
        assertThat(accounts.findById(goodAccount.getId()).orElseThrow().getCashBalance()).isEqualTo(931000);
        assertThat(orders.findById(goodOrder.getId()).orElseThrow().getStatus()).isEqualTo(Order.Status.EXECUTED);
        assertThat(executions.count()).isEqualTo(1);
        assertThat(listener.response(goodAccount.getId(), goodOrder.getId()).executions()).hasSize(1);
        verify(subscriptions, atLeastOnce()).refresh();
    }

    @Test
    void initialRestFailureLeavesPendingThenWebsocketPriceExecutes() {
        var account = account("첫 가격 대기");
        var order = order(account);
        when(stream.latest("005930")).thenReturn(Optional.empty());
        when(market.currentPrice("005930")).thenThrow(new IllegalStateException("test provider failure"));
        listener.orderCreated(order);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(Order.Status.PENDING);
        assertThat(executions.count()).isZero();

        events.publishEvent(price());
        var response = listener.response(account.getId(), order.getId());
        assertThat(response.orderStatus()).isEqualTo("executed");
        assertThat(response.reservedCash()).isZero();
        assertThat(response.executions()).hasSize(1);
        assertThat(response.executions().getFirst().executionPrice()).isEqualTo(69000);
        assertThat(response.executions().getFirst().realizedPnl()).isNull();
    }
}