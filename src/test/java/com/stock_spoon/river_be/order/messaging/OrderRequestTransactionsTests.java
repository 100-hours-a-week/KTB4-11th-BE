package com.stock_spoon.river_be.order.messaging;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.execution.repository.ExecutionRepository;
import com.stock_spoon.river_be.execution.service.ExecutionTestFixture;
import com.stock_spoon.river_be.execution.service.MarketOrderPricingService;
import com.stock_spoon.river_be.holding.entity.Holding;
import com.stock_spoon.river_be.holding.repository.HoldingRepository;
import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.order.repository.OrderRequestRecordRepository;
import com.stock_spoon.river_be.order.reservation.OrderReservationStore;
import com.stock_spoon.river_be.order.service.OrderService;
import com.stock_spoon.river_be.order.validator.OrderMarketValidator;
import com.stock_spoon.river_be.market.OrderBook;
import com.stock_spoon.river_be.market.QuoteEvent;
import com.stock_spoon.river_be.market.QuoteLevel;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class OrderRequestTransactionsTests {
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired HoldingRepository holdings;
    @Autowired ExecutionRepository executions;
    @Autowired OrderRequestRecordRepository records;
    @Autowired OrderService orderService;
    @Autowired EntityManager em;
    @Autowired Validator validator;
    @Autowired PlatformTransactionManager manager;
    final MemoryReservations store = new MemoryReservations();
    OrderRequestTransactions processing;
    Account account;
    final Instant now = Instant.parse("2026-10-22T01:00:00Z");

    @BeforeEach void setup() {
        account = accounts.save(new Account(users.save(new User("queue-test")), "manual", 100000, false));
        var execution = ExecutionTestFixture.withClock(accounts, orders, holdings, executions, Clock.fixed(now, ZoneOffset.UTC));
        processing = new OrderRequestTransactions(accounts, records, em, manager, orderService, execution,
                new OrderRequestValidator(validator, new OrderMarketValidator()), store,
                new MarketOrderPricingService(), Clock.fixed(now, ZoneOffset.UTC));
    }
    @Test void concurrentBuyReservationsCannotSpendSameCash() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> task = () -> { start.await(); try { processing.admit(message("buy", "limit", 70000L, 1), null); return true; }
                catch (com.stock_spoon.river_be.order.exception.OrderException e) { return false; } };
            var a = pool.submit(task); var b = pool.submit(task); start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }
    @Test void sellReservationsProtectOtherPendingRequests() {
        holdings.save(new Holding(account, "005930", 10, BigDecimal.valueOf(100000)));
        processing.admit(message("sell", "limit", 10000L, 7), null);
        assertThatThrownBy(() -> processing.admit(message("sell", "limit", 10000L, 7), null)).hasMessageContaining("수량");
    }
    @Test void duplicateDeliveryAndStaleRedisReservationDoNotDoubleReserve() {
        var a = message("buy", "limit", 70000L, 1);
        processing.admit(a, null);
        var first = processing.process(a, null, Map.of(), () -> {});
        assertThat(processing.process(a, null, Map.of(), () -> {}).orderId()).isEqualTo(first.orderId());
        processing.admit(message("buy", "limit", 30000L, 1), null);
        assertThat(orderService.availableCash(account.getId())).isEqualTo(30000);
        assertThat(store.list(account.getId())).hasSize(2);
    }
    @Test void marketPriceIncreaseUsesSpareCashButProtectsOthers() {
        var a = message("buy", "market", null, 1);
        processing.admit(a, quote(70000));
        processing.admit(message("buy", "limit", 20000L, 1), null);
        var result = processing.process(a, quote(75000), Map.of(), () -> {});
        assertThat(result.status()).isEqualTo("CREATED");
        assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(25000);
        assertThat(orders.findById(result.orderId()).orElseThrow().getStatus()).isEqualTo(Order.Status.EXECUTED);
    }
    @Test void marketCannotConsumeAnotherReservationsCash() {
        var a = message("buy", "market", null, 1);
        processing.admit(a, quote(70000));
        processing.admit(message("buy", "limit", 20000L, 1), null);
        assertThatThrownBy(() -> processing.process(a, quote(85000), Map.of(), () -> {})).hasMessageContaining("현금");
        assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(100000);
        assertThat(records.findById(a.eventId())).isEmpty();
    }
    @Test void sameIdDifferentPayloadIsRejected() {
        var a = message("buy", "limit", 70000L, 1);
        processing.admit(a, null);
        var changed = new OrderRequestMessage(a.eventId(), a.correlationId(), a.userId(), a.accountId(), a.source(), a.requestedAt(),
                new OrderCreateRequest("005930", "buy", "limit", 80000L, 1L, null, null, List.of(), null, null));
        assertThatThrownBy(() -> processing.admit(changed, null)).hasMessageContaining("다른");
    }
    @Test void marketFailureRollsBackOrderAssetAndResultTogether() {
        var a=message("buy","market",null,1);
        processing.admit(a,quote(70000));
        assertThatThrownBy(()->processing.process(a,quote(70000),Map.of(),()->{throw new IllegalStateException("source changed");}))
                .hasMessage("source changed");
        assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(100000);
        assertThat(records.findById(a.eventId())).isEmpty();
        assertThat(store.list(account.getId())).hasSize(1);
    }
    @Test void delayedMarketRequestIsRejectedAndCannotExecuteOnRedelivery() {
        var original=message("buy","market",null,1);
        var expired=new OrderRequestMessage(original.eventId(),original.correlationId(),original.userId(),original.accountId(),
                original.source(),now.minusSeconds(86400),original.request());
        store.add(new OrderReservationStore.Reservation(expired,70000,null));
        var service=service(org.mockito.Mockito.mock(OrderRequestPublisher.class));
        assertThat(service.consume(expired).reasonCode()).isEqualTo("ORDER_REQUEST_EXPIRED");
        assertThat(service.consume(expired).status()).isEqualTo("REJECTED");
        assertThat(store.list(account.getId())).isEmpty();
        assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(100000);
    }
    @Test void uncertainPublicationKeepsReservationForSameIdRetry() {
        var publisher=org.mockito.Mockito.mock(OrderRequestPublisher.class);
        org.mockito.Mockito.when(publisher.publish(org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("timeout"));
        var service=service(publisher);
        var m=message("buy","limit",70000L,1);
        assertThatThrownBy(()->service.submit(m.userId(),m.accountId(),m.eventId(),m.request())).hasMessageContaining("전송");
        assertThat(store.list(account.getId())).hasSize(1);
        assertThatThrownBy(()->processing.admit(message("buy","limit",70000L,1),null)).hasMessageContaining("현금");
        org.mockito.Mockito.doReturn("sqs-id").when(publisher).publish(org.mockito.ArgumentMatchers.any());
        service.submit(m.userId(),m.accountId(),m.eventId(),m.request());
        assertThat(store.list(account.getId())).hasSize(1);
    }
    @Test void crossAccountEventCollisionReleasesOnlyLosingReservation() {
        var a=message("buy","limit",70000L,1);
        processing.admit(a,null);
        var other=accounts.save(new Account(account.getUser(),"second",100000,false));
        var b=new OrderRequestMessage(a.eventId(),a.correlationId(),a.userId(),other.getId(),a.source(),a.requestedAt(),a.request());
        processing.admit(b,null);
        processing.process(a,null,Map.of(),()->{});
        assertThatThrownBy(()->service(org.mockito.Mockito.mock(OrderRequestPublisher.class)).consume(b)).hasMessageContaining("다른");
        assertThat(store.list(other.getId())).isEmpty();
        assertThat(store.list(account.getId())).hasSize(1);
        assertThat(records.findById(a.eventId()).orElseThrow().accountId()).isEqualTo(account.getId());
    }
    OrderRequestService service(OrderRequestPublisher publisher) {
        return new OrderRequestService(processing,store,publisher,
                org.mockito.Mockito.mock(com.stock_spoon.river_be.order.service.OrderSubscriptionService.class),
                org.mockito.Mockito.mock(com.stock_spoon.river_be.market.MarketStream.class),
                org.mockito.Mockito.mock(com.stock_spoon.river_be.execution.service.OrderBuyPriceService.class),orders,
                org.mockito.Mockito.mock(com.stock_spoon.river_be.execution.listener.OrderExecutionListener.class),Clock.fixed(now,ZoneOffset.UTC));
    }
    OrderRequestMessage message(String side, String type, Long price, long qty) {
        return new OrderRequestMessage(UUID.randomUUID().toString(), "test", account.getUser().getId(), account.getId(),
                Order.Source.USER, now, new OrderCreateRequest("005930", side, type, price, qty, null, null, List.of(), null, null));
    }
    QuoteEvent quote(long price) {
        var book = new OrderBook("005930", List.of(new QuoteLevel(BigDecimal.valueOf(price), 10)),
                List.of(new QuoteLevel(BigDecimal.valueOf(price), 10)), LocalTime.of(10,0), now);
        return new QuoteEvent("005930", "orderbook", now, null, null, book);
    }
    static class MemoryReservations implements OrderReservationStore {
        final Map<Long, Map<String, Reservation>> data = new ConcurrentHashMap<>();
        public List<Reservation> list(long id) { return List.copyOf(data.getOrDefault(id, Map.of()).values()); }
        public void add(Reservation r) { data.computeIfAbsent(r.message().accountId(), k -> new ConcurrentHashMap<>()).putIfAbsent(r.message().eventId(), r); }
        public void remove(long account, String id) { data.getOrDefault(account, new ConcurrentHashMap<>()).remove(id); }
        public void published(Reservation r, Instant at) { }
        public List<Long> accounts() { return List.copyOf(data.keySet()); }
    }
}
