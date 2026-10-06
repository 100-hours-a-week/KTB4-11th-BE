package com.stock_spoon.river_be.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.order.Holding;
import com.stock_spoon.river_be.order.HoldingRepository;
import com.stock_spoon.river_be.order.Order;
import com.stock_spoon.river_be.order.OrderRepository;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import com.stock_spoon.river_be.user.service.AiUserSnapshotService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@Transactional
class AiUserSnapshotServiceTests {
    @Autowired AiUserSnapshotService service;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired HoldingRepository holdings;
    @Autowired OrderRepository orders;
    @Autowired EntityManager entityManager;
    @MockitoBean KiwoomStockStream stream;

    @Test
    void includesEveryUserButOnlyActiveAiManagedAccountsAndPendingOrders() {
        var firstUser = users.save(new User("첫 사용자"));
        var userWithoutAccount = users.save(new User("계좌 없는 사용자"));
        var active = accounts.save(new Account(firstUser, "AI 계좌", 1_000_000));
        var unmanaged = accounts.save(new Account(firstUser, "직접 운용 계좌", 1_000_000));
        var inactive = accounts.save(new Account(firstUser, "비활성 계좌", 1_000_000));
        holdings.save(new Holding(active, "005930", 10, new BigDecimal("1000000.00")));
        var pending = orders.save(Order.pendingLimit(active, "005930", Order.Side.BUY, 1,
                150_000, Order.Source.AI, "매수", Instant.parse("2026-09-28T01:00:00Z")));
        var cancelled = Order.pendingLimit(active, "005930", Order.Side.SELL, 2,
                250_000, Order.Source.AI, "매도", Instant.parse("2026-09-28T01:00:00Z"));
        cancelled.cancel(Instant.parse("2026-09-28T01:01:00Z"));
        orders.save(cancelled);

        entityManager.flush();
        entityManager.createNativeQuery("update accounts set is_ai_managed = false where account_id = :id")
                .setParameter("id", unmanaged.getId()).executeUpdate();
        entityManager.createNativeQuery("update accounts set is_active = false where account_id = :id")
                .setParameter("id", inactive.getId()).executeUpdate();
        entityManager.clear();

        var result = service.snapshot();
        assertEquals(2, result.size());
        assertEquals(firstUser.getId(), result.get(0).userId());
        assertEquals(userWithoutAccount.getId(), result.get(1).userId());
        assertTrue(result.get(1).accounts().isEmpty());

        var account = result.get(0).accounts().getFirst();
        assertEquals(1, result.get(0).accounts().size());
        assertEquals(active.getId(), account.accountId());
        assertEquals(1_000_000, account.cashBalance());
        assertTrue(account.active());
        assertEquals("005930", account.stocks().getFirst().stockCode());
        assertEquals(new BigDecimal("1000000.00"), account.stocks().getFirst().totalCost());
        assertEquals(10, account.stocks().getFirst().quantity());
        assertEquals(1, account.pendingOrders().size());
        assertEquals(pending.getId(), account.pendingOrders().getFirst().orderId());
        assertEquals("005930", account.pendingOrders().getFirst().stockCode());
        verify(stream, never()).latest("005930");
    }

    @Test
    void includesPendingOrdersWithoutReadingCurrentPrices() {
        var user = users.save(new User("사용자"));
        var first = accounts.save(new Account(user, "첫 계좌", 1_000_000));
        var second = accounts.save(new Account(user, "둘째 계좌", 1_000_000));
        for (var account : java.util.List.of(first, second)) {
            orders.save(Order.pendingLimit(account, "005930", Order.Side.BUY, 1, 150_000,
                    Order.Source.AI, null, Instant.now()));
        }
        var result = service.snapshot();
        assertEquals(2, result.getFirst().accounts().size());
        verify(stream, never()).latest("005930");

        orders.save(Order.pendingLimit(second, "000660", Order.Side.BUY, 1, 250_000,
                Order.Source.AI, null, Instant.now()));
        assertEquals(2, service.snapshot().getFirst().accounts().size());
        verify(stream, never()).latest("000660");
    }
}
