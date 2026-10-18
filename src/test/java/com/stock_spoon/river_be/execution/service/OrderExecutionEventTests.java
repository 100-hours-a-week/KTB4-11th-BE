package com.stock_spoon.river_be.execution.service;

import com.stock_spoon.river_be.order.service.OrderSubscriptionService;

import com.stock_spoon.river_be.holding.entity.Holding;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.execution.listener.OrderExecutionListener;
import com.stock_spoon.river_be.execution.repository.ExecutionRepository;
import com.stock_spoon.river_be.holding.repository.HoldingRepository;
import com.stock_spoon.river_be.order.repository.OrderRepository;

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
    private static final Instant NOW = Instant.parse("2026-10-19T01:00:00Z");
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired OrderRepository orders;
    @Autowired HoldingRepository holdings;
    @Autowired ExecutionRepository executions;
    @Autowired ApplicationEventPublisher events;
    @Autowired OrderExecutionListener listener;
    @Autowired OrderExecutionService executionService;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @MockitoBean KiwoomStockStream stream;
    @MockitoBean KiwoomMarketClient market;
    @MockitoBean OrderSubscriptionService subscriptions;

    @org.junit.jupiter.api.BeforeEach
    void setupQuoteAdapter() {
        doCallRealMethod().when(stream).latestQuote(org.mockito.ArgumentMatchers.anyString());
    }

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
                Order.Source.AI, null, NOW.minusSeconds(1)));
    }

    private KiwoomStockStream.StockPrice price() {
        return new KiwoomStockStream.StockPrice("005930", new BigDecimal("69000"),
                BigDecimal.ZERO, BigDecimal.ZERO, LocalTime.of(10, 0), NOW);
    }

    @Test
    void pendingOrdersLoadReportsWithoutOneExtraQueryPerOrder() {
        var account = account("조회 부하 검증");
        var batch = new java.util.ArrayList<Order>();
        for (int i = 0; i < 20; i++) batch.add(Order.pendingLimit(account, "005930", Order.Side.BUY,
                1, 70000, Order.Source.AI, i % 2 == 0 ? "매수 보고서" : null, NOW.minusSeconds(1)));
        orders.saveAll(batch);
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        try {
            statistics.setStatisticsEnabled(true); statistics.clear();
            var found = orders.findPendingForPrice("005930", Order.Status.PENDING, Order.Type.LIMIT);
            assertThat(found).hasSize(20);
            assertThat(found.stream().filter(order -> order.getReport() != null).count()).isEqualTo(10);
            assertThat(statistics.getPrepareStatementCount()).as("보고서 확인으로 주문 수만큼 추가 조회하지 않음").isLessThanOrEqualTo(2);
        } finally { statistics.setStatisticsEnabled(enabled); }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "REDIS_LIVE_TEST", matches = "true")
    void twoBeSubscribersExecuteSharedQuoteOnlyOnce() throws Exception {
        var connection = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory("localhost", 6380);
        connection.afterPropertiesSet();
        connection.start();
        var redis = new org.springframework.data.redis.core.StringRedisTemplate(connection);
        var store = new com.stock_spoon.river_be.market.RedisQuoteStore(redis);
        String code = "TST005";
        String owner = java.util.UUID.randomUUID().toString();
        var source = new com.stock_spoon.river_be.market.QuoteStore.Source(owner, java.util.UUID.randomUUID().toString());
        var account = account("공유 시세 체결");
        var order = orders.save(Order.pendingLimit(account, code, Order.Side.BUY, 1, 70000,
                Order.Source.AI, null, NOW.minusSeconds(1)));
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(2);
        var containers = new java.util.ArrayList<org.springframework.data.redis.listener.RedisMessageListenerContainer>();
        var streams = new java.util.ArrayList<com.stock_spoon.river_be.market.RedisMarketStream>();
        try {
            assertThat(redis.hasKey("coord:kiwoom:collector")).isFalse();
            redis.opsForValue().set("coord:kiwoom:collector", owner, java.time.Duration.ofSeconds(15));
            store.remove(code);
            for (int i = 0; i < 2; i++) {
                var channel = new org.springframework.data.redis.listener.RedisMessageListenerContainer();
                channel.setConnectionFactory(connection);
                channel.afterPropertiesSet();
                channel.start();
                containers.add(channel);
                var handler = new java.util.concurrent.atomic.AtomicReference<OrderExecutionListener>();
                var shared = new com.stock_spoon.river_be.market.RedisMarketStream(store,
                        mock(com.stock_spoon.river_be.market.AiQuoteAdapter.class), mock(com.stock_spoon.river_be.market.kiwoom.RedisCollectorHeartbeat.class),
                        channel, event -> {
                            if (!(event instanceof com.stock_spoon.river_be.market.RedisQuoteStore.QuoteEvent quote)) return;
                            ready.countDown();
                            try {
                                if (!start.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("동시 처리 대기 실패");
                                handler.get().onQuote(quote);
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(error);
                            } finally { finished.countDown(); }
                        }, orders);
                streams.add(shared);
                handler.set(new OrderExecutionListener(orders, executions, executionService,
                        subscriptions, shared, market, mock(OrderBuyPriceService.class)));
                shared.updateSymbols(java.util.Set.of(code));
            }
            store.register(code, source, Instant.now());
            store.savePrice(new KiwoomStockStream.StockPrice(code, new BigDecimal("69000"),
                    BigDecimal.ZERO, BigDecimal.ZERO, LocalTime.of(10, 0), NOW), source);
            assertThat(ready.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(Order.Status.EXECUTED);
            assertThat(executions.count()).isEqualTo(1);
            assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(931000);
        } finally {
            start.countDown();
            streams.forEach(com.stock_spoon.river_be.market.RedisMarketStream::close);
            for (var container : containers) container.destroy();
            store.remove(code);
            if (owner.equals(redis.opsForValue().get("coord:kiwoom:collector"))) redis.delete("coord:kiwoom:collector");
            connection.destroy();
        }
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
    void missingInitialPriceLeavesPendingThenWebsocketPriceExecutes() {
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

    @Test
    void fridayAndWeekendOrdersIgnoreOldPriceAndExecuteOnMondayNewEventOnlyOnce() {
        var firstAccount = account("금요일 예약");
        var secondAccount = account("주말 예약");
        var friday = orders.save(Order.pendingLimit(firstAccount, "005930", Order.Side.BUY, 1, 70000,
                Order.Source.AI, null, Instant.parse("2026-10-16T12:00:00Z")));
        var weekend = orders.save(Order.pendingLimit(secondAccount, "005930", Order.Side.BUY, 1, 70000,
                Order.Source.AI, null, Instant.parse("2026-10-17T12:00:00Z")));
        // 재시작 후 최초 판단은 장외 예약에 대해 저장 시세를 조회하지 않는다.
        listener.orderCreated(friday);
        listener.orderCreated(weekend);
        verify(stream, never()).latestQuote(anyString());
        verifyNoInteractions(market);
        events.publishEvent(new KiwoomStockStream.StockPrice("005930", BigDecimal.valueOf(69000),
                BigDecimal.ZERO, BigDecimal.ZERO, LocalTime.of(10, 0), Instant.parse("2026-10-16T01:00:00Z")));
        assertThat(executions.count()).isZero();
        assertThat(orders.findById(friday.getId()).orElseThrow().getStatus()).isEqualTo(Order.Status.PENDING);
        events.publishEvent(price());
        events.publishEvent(price());
        assertThat(executions.count()).isEqualTo(2);
        assertThat(orders.findById(friday.getId()).orElseThrow().getStatus()).isEqualTo(Order.Status.EXECUTED);
        assertThat(orders.findById(weekend.getId()).orElseThrow().getStatus()).isEqualTo(Order.Status.EXECUTED);
        assertThat(accounts.findById(firstAccount.getId()).orElseThrow().getCashBalance()).isEqualTo(931000);
    }
}
