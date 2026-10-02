package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MarketBuyTransactionTests {
    private static final Instant NOW = Instant.parse("2026-10-01T01:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean @Primary
        OrderExecutionService fixedExecution(AccountRepository accounts, OrderRepository orders,
                HoldingRepository holdings, ExecutionRepository executions) {
            return new OrderExecutionService(accounts, orders, holdings, executions,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }

    @Autowired OrderExecutionService execution;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired HoldingRepository holdings;
    @MockitoSpyBean ExecutionRepository executions;
    @Autowired PlatformTransactionManager transactions;
    @Autowired jakarta.persistence.EntityManager entityManager;

    private Account account() {
        return new TransactionTemplate(transactions).execute(ignored ->
                accounts.save(new Account(users.save(new User("시장가 검증")), "시장가 계좌", 1000000)));
    }

    private KiwoomStockStream.OrderBook book(Instant receipt, LocalTime time) {
        return new KiwoomStockStream.OrderBook("005930", List.of(
                new KiwoomStockStream.QuoteLevel(BigDecimal.valueOf(70000), 3),
                new KiwoomStockStream.QuoteLevel(BigDecimal.valueOf(70100), 4),
                new KiwoomStockStream.QuoteLevel(BigDecimal.valueOf(70200), 5)), List.of(), time, receipt);
    }

    private Order buy(Account account, KiwoomStockStream.OrderBook book) {
        return execution.executeMarketBuy(account.getUser().getId(), account.getId(), "005930", 10, "근거", book);
    }

    private void unchanged(Account account) {
        assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(1000000);
        assertThat(orders.findAllByAccountIdAndStatus(account.getId(), Order.Status.EXECUTED)).isEmpty();
        assertThat(orders.findAllByAccountIdAndStatus(account.getId(), Order.Status.PENDING)).isEmpty();
        assertThat(holdings.findByAccountIdAndStockCode(account.getId(), "005930")).isEmpty();
    }

    @Test
    void laterExecutionSaveFailureRollsBackOrderAllExecutionsAndAssets() {
        var account = account();
        long before = executions.count();
        var calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("second fill failed");
            Execution fill = call.getArgument(0);
            entityManager.persist(fill);
            entityManager.flush();
            return fill;
        }).when(executions).save(any(Execution.class));
        assertThatThrownBy(() -> buy(account, book(NOW, LocalTime.of(10, 0))))
                .isInstanceOf(IllegalStateException.class).hasMessage("second fill failed");
        unchanged(account);
        assertThat(executions.count()).isEqualTo(before);
    }

    @Test
    void concurrentBuysCannotSpendSameAccountCashTwice() throws Exception {
        var account = account();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(ignored -> pool.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                try {
                    buy(account, book(NOW, LocalTime.of(10, 0)));
                    return true;
                } catch (OrderException error) {
                    assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                    assertThat(error.getMessage()).contains("현금");
                    return false;
                }
            })).toList();
            start.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(15, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
        }
        assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(299000);
        assertThat(orders.findAllByAccountIdAndStatus(account.getId(), Order.Status.EXECUTED)).hasSize(1);
        assertThat(holdings.findByAccountIdAndStockCode(account.getId(), "005930").orElseThrow().getQuantity()).isEqualTo(10);
    }

    @Test
    void ageBoundariesAndOneSecondFutureQuoteAreAccepted() {
        for (var snapshot : List.of(book(NOW.minusSeconds(5), LocalTime.of(9, 59, 55)),
                book(NOW, LocalTime.of(10, 0, 1)))) {
            var order = buy(account(), snapshot);
            assertThat(order.getStatus()).isEqualTo(Order.Status.EXECUTED);
            assertThat(executions.findForOrder(order.getId())).hasSize(3);
        }
    }

    @Test
    void closeAndBeforeOpenFailBeforePersisting() {
        for (var instant : List.of(Instant.parse("2026-10-01T06:30:00Z"),
                Instant.parse("2026-10-01T00:00:00Z").minusNanos(1))) {
            var account = account();
            var service = new OrderExecutionService(accounts, orders, holdings, executions,
                    Clock.fixed(instant, ZoneOffset.UTC));
            assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(ignored ->
                    service.executeMarketBuy(account.getUser().getId(), account.getId(),
                    "005930", 10, "근거", book(instant, instant.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalTime()))))
                    .isInstanceOfSatisfying(OrderException.class,
                            error -> assertThat(error.code()).isEqualTo("ORDER_WINDOW_CLOSED"));
            unchanged(account);
        }
    }

    @Test
    void malformedQuotePriceIsMarketDataFailureWithoutSaving() {
        var account = account();
        var invalid = new KiwoomStockStream.OrderBook("005930", List.of(
                new KiwoomStockStream.QuoteLevel(new BigDecimal("70000.5"), 10)),
                List.of(), LocalTime.of(10, 0), NOW);
        assertThatThrownBy(() -> buy(account, invalid)).isInstanceOfSatisfying(OrderException.class,
                error -> assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        unchanged(account);
    }

    private Account seller(long quantity, String cost) {
        var account = account();
        new TransactionTemplate(transactions).executeWithoutResult(ignored ->
                holdings.save(new Holding(account, "005930", quantity, new BigDecimal(cost))));
        return account;
    }

    private KiwoomStockStream.OrderBook sellBook() {
        return new KiwoomStockStream.OrderBook("005930", List.of(), List.of(
                new KiwoomStockStream.QuoteLevel(BigDecimal.valueOf(70000), 5),
                new KiwoomStockStream.QuoteLevel(BigDecimal.valueOf(70200), 3),
                new KiwoomStockStream.QuoteLevel(BigDecimal.valueOf(70100), 4)), LocalTime.of(10, 0), NOW);
    }

    private Order sell(Account account, long quantity) {
        return execution.executeMarketSell(account.getUser().getId(), account.getId(), "005930", quantity, "매도 근거", sellBook());
    }

    @Test
    void fullMarketSellRemovesHoldingAndKeepsAllRealizedExecutions() {
        var account = seller(10, "600000");
        var order = sell(account, 10);
        assertThat(order.getSide()).isEqualTo(Order.Side.SELL);
        assertThat(order.getStatus()).isEqualTo(Order.Status.EXECUTED);
        assertThat(order.getLimitPrice()).isNull();
        assertThat(order.getReservedCash()).isZero();
        assertThat(holdings.findByAccountIdAndStockCode(account.getId(), "005930")).isEmpty();
        assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(1701000);
        var fills = executions.findForOrder(order.getId());
        assertThat(fills).extracting(Execution::getQuantity).containsExactly(3L, 4L, 3L);
        assertThat(fills).extracting(Execution::getPrice).containsExactly(70200L, 70100L, 70000L);
        assertThat(fills.stream().map(Execution::getRealizedPnl).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("101000.00");
    }

    @Test
    void partialMarketSellUsesUnchangedFractionalAverageCostAndRoundsOnlyAtStorage() {
        var account = seller(3, "200000");
        var book = new KiwoomStockStream.OrderBook("005930", List.of(), List.of(
                new KiwoomStockStream.QuoteLevel(BigDecimal.valueOf(69000), 1),
                new KiwoomStockStream.QuoteLevel(BigDecimal.valueOf(70000), 1)), LocalTime.of(10, 0), NOW);
        var order = execution.executeMarketSell(account.getUser().getId(), account.getId(), "005930", 2, "매도 근거", book);
        var fills = executions.findForOrder(order.getId());
        assertThat(fills.get(0).getRealizedPnl()).isEqualByComparingTo("3333.33");
        assertThat(fills.get(0).getRealizedReturnPercent()).isEqualByComparingTo("5.0000");
        assertThat(fills.get(1).getRealizedPnl()).isEqualByComparingTo("2333.33");
        assertThat(fills.get(1).getRealizedReturnPercent()).isEqualByComparingTo("3.5000");
        var held = holdings.findByAccountIdAndStockCode(account.getId(), "005930").orElseThrow();
        assertThat(held.getQuantity()).isEqualTo(1);
        assertThat(held.getTotalCost()).isEqualByComparingTo("66666.67");
        assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(1139000);
    }

    @Test
    void failedMarketSellRestoresDeletedOrReducedHoldingAndEverySavedFill() {
        for (long quantity : new long[] {10, 20}) {
            var account = seller(quantity, quantity == 10 ? "600000" : "1200000");
            long before = executions.count();
            var calls = new AtomicInteger();
            doAnswer(call -> {
                if (calls.incrementAndGet() == 2) throw new IllegalStateException("second sell fill failed");
                Execution fill = call.getArgument(0);
                entityManager.persist(fill);
                entityManager.flush();
                return fill;
            }).when(executions).save(any(Execution.class));
            assertThatThrownBy(() -> sell(account, 10)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("second sell fill failed");
            assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(1000000);
            var held = holdings.findByAccountIdAndStockCode(account.getId(), "005930").orElseThrow();
            assertThat(held.getQuantity()).isEqualTo(quantity);
            assertThat(held.getTotalCost()).isEqualByComparingTo(quantity == 10 ? "600000" : "1200000");
            assertThat(orders.findAllByAccountIdAndStatus(account.getId(), Order.Status.PENDING)).isEmpty();
            assertThat(orders.findAllByAccountIdAndStatus(account.getId(), Order.Status.EXECUTED)).isEmpty();
            assertThat(executions.count()).isEqualTo(before);
        }
    }

    @Test
    void concurrentMarketSellsCannotUseSameSharesTwice() throws Exception {
        var account = seller(15, "900000");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(ignored -> pool.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                try {
                    sell(account, 10);
                    return true;
                } catch (OrderException error) {
                    assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                    assertThat(error.getMessage()).contains("수량");
                    return false;
                }
            })).toList();
            start.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(15, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
        }
        assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(1701000);
        var held = holdings.findByAccountIdAndStockCode(account.getId(), "005930").orElseThrow();
        assertThat(held.getQuantity()).isEqualTo(5);
        assertThat(held.getTotalCost()).isEqualByComparingTo("300000");
        assertThat(orders.findAllByAccountIdAndStatus(account.getId(), Order.Status.EXECUTED)).hasSize(1);
    }

    @Test
    void marketSellCloseAndCashOverflowRejectWithoutChangingAssets() {
        var account = seller(10, "600000");
        var closed = new OrderExecutionService(accounts, orders, holdings, executions,
                Clock.fixed(Instant.parse("2026-10-01T06:30:00Z"), ZoneOffset.UTC));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(ignored ->
                closed.executeMarketSell(account.getUser().getId(), account.getId(), "005930", 10, "근거", sellBook())))
                .isInstanceOfSatisfying(OrderException.class, error ->
                        assertThat(error.code()).isEqualTo("ORDER_WINDOW_CLOSED"));
        new TransactionTemplate(transactions).executeWithoutResult(ignored ->
                accounts.findById(account.getId()).orElseThrow().changeCash(Long.MAX_VALUE - 1000000));
        assertThatThrownBy(() -> sell(account, 10)).isInstanceOfSatisfying(OrderException.class,
                error -> assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));
        assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(Long.MAX_VALUE);
        assertThat(holdings.findByAccountIdAndStockCode(account.getId(), "005930").orElseThrow().getQuantity()).isEqualTo(10);
        assertThat(orders.findAllByAccountIdAndStatus(account.getId(), Order.Status.EXECUTED)).isEmpty();
    }

}
