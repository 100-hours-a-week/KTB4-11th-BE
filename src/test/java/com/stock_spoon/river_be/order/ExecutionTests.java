package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class ExecutionTests {
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired OrderRepository orders;
    @Autowired ExecutionRepository executions;
    @Autowired EntityManager em;

    @Test
    void persistsOrderLinkAndPolicyPrecision() {
        var account = accounts.save(new Account(users.save(new User("체결테스트")), "AI 계좌", 1000000));
        var order = orders.save(Order.pendingLimit(account, "005930", Order.Side.SELL,
                1, 70000, Order.Source.AI, null, null, Instant.now()));
        var execution = executions.save(new Execution(order, 70100, 1,
                new BigDecimal("123.455"), new BigDecimal("1.23455"), Instant.now()));
        em.flush();
        em.clear();
        var stored = executions.findById(execution.getId()).orElseThrow();
        assertThat(stored.getOrderId()).isEqualTo(order.getId());
        assertThat(stored.getPrice()).isEqualTo(70100);
        assertThat(stored.getQuantity()).isEqualTo(1);
        assertThat(stored.getRealizedPnl()).isEqualByComparingTo("123.46");
        assertThat(stored.getRealizedReturnPercent()).isEqualByComparingTo("1.2346");
        assertThat(stored.getCreatedAt()).isNotNull();
    }
}