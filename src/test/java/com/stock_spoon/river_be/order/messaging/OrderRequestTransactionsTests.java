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
    // [계좌 비활성화 테스트] 장중 시세가 없어도 비활성 계좌 메시지는 거절로 종결하고 재수신 시 같은 결과를 반환한다.
    @Test void inactiveMarketRequestIsRejectedBeforeObtainingQuotes() {
        var request = message("buy","market",null,1);
        processing.admit(request,quote(70000));
        deactivateAccount();
        var service = service(org.mockito.Mockito.mock(OrderRequestPublisher.class));
        assertThat(service.consume(request).reasonCode()).isEqualTo("ACCOUNT_INACTIVE");
        assertThat(service.consume(request).status()).isEqualTo("REJECTED");
        assertThat(store.list(account.getId())).isEmpty();
        assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(100000);
    }

    // 기존 복구 작업이 비활성 계좌의 미발행 예약을 SQS로 다시 보내지 않아야 한다.
    @Test void reconciliationTerminatesInactiveReservationsWithoutPublishing() {
        var request=message("buy","limit",70000L,1);
        processing.admit(request,null);
        deactivateAccount();
        var publisher=org.mockito.Mockito.mock(OrderRequestPublisher.class);
        service(publisher).reconcile();
        assertThat(records.findById(request.eventId()).orElseThrow().reasonCode()).isEqualTo("ACCOUNT_INACTIVE");
        assertThat(store.list(account.getId())).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(publisher);
    }

    @Test void automaticLimitExecutionDoesNotTradeAnInactiveAccount() {
        var request=message("buy","limit",70000L,1);
        processing.admit(request,null);
        var result=processing.process(request,null,Map.of(),()->{});
        deactivateAccount();
        var execution=ExecutionTestFixture.withClock(accounts,orders,holdings,executions,Clock.fixed(now,ZoneOffset.UTC));
        Boolean executed = new TransactionTemplate(manager).execute(s -> execution.executeLimit(account.getId(),result.orderId(),65000));
        assertThat(executed).isFalse();
        assertThat(orders.findById(result.orderId()).orElseThrow().getStatus()).isEqualTo(Order.Status.PENDING);
    }

    // DB 거절 결과 커밋 후 Redis 삭제만 실패하면 예약을 남겨 기존 정리 작업이 안전하게 재시도할 수 있다.
    @Test void inactiveReservationCleanupRetriesAfterRedisFailure() {
        var request=message("buy","limit",70000L,1);
        processing.admit(request,null);
        deactivateAccount();
        store.failRemoval=true;
        assertThatThrownBy(() -> processing.cleanupInactiveAccount(account.getId())).hasMessage("redis unavailable");
        assertThat(records.findById(request.eventId()).orElseThrow().reasonCode()).isEqualTo("ACCOUNT_INACTIVE");
        assertThat(store.list(account.getId())).hasSize(1);
        store.failRemoval=false;
        assertThat(processing.cleanupInactiveAccount(account.getId())).isTrue();
        assertThat(store.list(account.getId())).isEmpty();
    }

    // 같은 계좌의 체결과 비활성화를 동시에 시작해 먼저 잠금을 얻은 작업의 결과가 일관되게 보존되는지 확인한다.
    @Test void deactivationAndLimitExecutionShareTheAccountLock() throws Exception {
        var request=message("buy","limit",70000L,1);
        processing.admit(request,null);
        var result=processing.process(request,null,Map.of(),()->{});
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();
        factory.addBean("processing",processing);
        var deletion=new com.stock_spoon.river_be.account.service.AccountDeletionService(accounts,orders,
                factory.getBeanProvider(OrderRequestTransactions.class),
                org.mockito.Mockito.mock(com.stock_spoon.river_be.order.service.OrderSubscriptionService.class),manager);
        var execution=ExecutionTestFixture.withClock(accounts,orders,holdings,executions,Clock.fixed(now,ZoneOffset.UTC));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var deleting=pool.submit(() -> { start.await();deletion.delete(account.getUser().getId(),account.getId());return true; });
            var trading=pool.submit(() -> {
                start.await();
                return new TransactionTemplate(manager).execute(s -> execution.executeLimit(account.getId(),result.orderId(),65000));
            });
            start.countDown();
            assertThat(deleting.get(10,TimeUnit.SECONDS)).isTrue();
            boolean traded=trading.get(10,TimeUnit.SECONDS);
            assertThat(orders.findById(result.orderId()).orElseThrow().getStatus())
                    .isEqualTo(traded?Order.Status.EXECUTED:Order.Status.CANCELLED);
            assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(traded?35000:100000);
            assertThat(accounts.findById(account.getId()).orElseThrow().isActive()).isFalse();
        }
    }

    void deactivateAccount() {
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            em.createNativeQuery("update accounts set is_active=false where account_id=:id")
                    .setParameter("id",account.getId()).executeUpdate();
            em.clear();
        });
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
        boolean failRemoval;
        public List<Reservation> list(long id) { return List.copyOf(data.getOrDefault(id, Map.of()).values()); }
        public void add(Reservation r) { data.computeIfAbsent(r.message().accountId(), k -> new ConcurrentHashMap<>()).putIfAbsent(r.message().eventId(), r); }
        public void remove(long account, String id) { data.getOrDefault(account, new ConcurrentHashMap<>()).remove(id); }
        public void removeAll(long account) {
            if(failRemoval) throw new IllegalStateException("redis unavailable");
            data.remove(account);
        }
        public void published(Reservation r, Instant at) { }
        public List<Long> accounts() { return List.copyOf(data.keySet()); }
    }
}
