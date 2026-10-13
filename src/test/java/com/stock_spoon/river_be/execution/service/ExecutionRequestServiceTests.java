package com.stock_spoon.river_be.execution.service;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.execution.repository.ExecutionRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:execution-request-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ExecutionRequestServiceTests {
    @Autowired ExecutionRequestService service;
    @Autowired ExecutionRequestRepository requests;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired PlatformTransactionManager transactions;
    private Account account() {
        return new TransactionTemplate(transactions).execute(status -> accounts.saveAndFlush(
                new Account(users.save(new User("요청검증")), "중복검증", 1000000)));
    }
    private Order action(Account account) {
        var locked = accounts.findLockedById(account.getId()).orElseThrow();
        locked.changeCash(-70000);
        var order = orders.save(Order.pendingMarket(locked, "005930", Order.Side.BUY, 1, "test", java.time.Instant.now()));
        order.execute();
        return order;
    }
    @Test
    void duplicateRequestAfterCommitReturnsOriginalOrderWithoutChangingCashAgain() {
        var account = account(); String id = java.util.UUID.randomUUID().toString();
        long user = new TransactionTemplate(transactions).execute(status -> accounts.findById(account.getId()).orElseThrow().getUser().getId());
        long order = service.execute(id, user, account.getId(), "a".repeat(64), () -> action(account));
        assertThat(service.execute(id, user, account.getId(), "a".repeat(64), () -> { throw new AssertionError("duplicate action"); })).isEqualTo(order);
        assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(930000);
        assertThat(service.result(id, user, account.getId(), "a".repeat(64))).contains(order);
        assertThatThrownBy(() -> service.execute(id, user, account.getId(), "b".repeat(64), () -> action(account)))
                .isInstanceOf(com.stock_spoon.river_be.order.exception.OrderException.class);
    }
    @Test
    void exceptionAfterAssetChangesRollsBackAssetsOrderAndRequestRecord() {
        var account = account(); String id = java.util.UUID.randomUUID().toString();
        long user = new TransactionTemplate(transactions).execute(status -> accounts.findById(account.getId()).orElseThrow().getUser().getId());
        assertThatThrownBy(() -> service.execute(id, user, account.getId(), "c".repeat(64), () -> {
            action(account); throw new IllegalStateException("simulated failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(requests.existsById(id)).isFalse();
        assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(1000000);
        assertThat(orders.findAllByAccountIdAndStatus(account.getId(), Order.Status.EXECUTED)).isEmpty();
    }
    @Test
    void concurrentSameRequestCommitsOnlyOneOrder() throws Exception {
        var account = account(); String id = java.util.UUID.randomUUID().toString();
        long user = new TransactionTemplate(transactions).execute(status -> accounts.findById(account.getId()).orElseThrow().getUser().getId());
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks = java.util.List.<java.util.concurrent.Callable<Long>>of(
                () -> service.execute(id, user, account.getId(), "d".repeat(64), () -> action(account)),
                () -> service.execute(id, user, account.getId(), "d".repeat(64), () -> action(account)));
            var results = executor.invokeAll(tasks);
            assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
        }
        assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(930000);
        assertThat(orders.findAllByAccountIdAndStatus(account.getId(), Order.Status.EXECUTED)).hasSize(1);
    }
    @Test
    void sameRequestIdAcrossAccountsMustRollbackSecondAssetChange() throws Exception {
        var first = account(); var second = account(); String id = java.util.UUID.randomUUID().toString();
        var tx = new TransactionTemplate(transactions);
        long firstUser = tx.execute(status -> accounts.findById(first.getId()).orElseThrow().getUser().getId());
        long secondUser = tx.execute(status -> accounts.findById(second.getId()).orElseThrow().getUser().getId());
        var secondStarted = new java.util.concurrent.CountDownLatch(1);
        var firstFuture = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Long>>();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            firstFuture.set(executor.submit(() -> service.execute(id, firstUser, first.getId(), "e".repeat(64), () -> {
                try {
                    if (!secondStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("second action did not start");
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                return action(first);
            })));
            var other = executor.submit(() -> service.execute(id, secondUser, second.getId(), "e".repeat(64), () -> {
                secondStarted.countDown();
                try { firstFuture.get().get(10, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception error) { throw new IllegalStateException(error); }
                return action(second);
            }));
            assertThat(firstFuture.get().get(15, java.util.concurrent.TimeUnit.SECONDS)).isPositive();
            assertThatThrownBy(() -> other.get(15, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);
        }
        assertThat(accounts.findById(second.getId()).orElseThrow().getCashBalance()).isEqualTo(1000000);
        assertThat(orders.findAllByAccountIdAndStatus(second.getId(), Order.Status.EXECUTED)).isEmpty();
    }
}
