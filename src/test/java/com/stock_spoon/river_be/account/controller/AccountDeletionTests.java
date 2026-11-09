package com.stock_spoon.river_be.account.controller;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.execution.entity.Execution;
import com.stock_spoon.river_be.holding.entity.Holding;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.messaging.OrderRequestTransactions;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 계좌 비활성화는 실제 보안·DB 커밋으로 검증하고 커밋 후 큐 정리만 대체한다. */
@SpringBootTest
class AccountDeletionTests {
    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired AccountRepository accounts;
    @Autowired JwtTokenProvider tokens;
    @Autowired com.stock_spoon.river_be.account.service.AccountService accountService;
    @MockitoSpyBean OrderRepository orders;
    @MockitoBean OrderRequestTransactions queue;
    MockMvc mvc;
    TransactionTemplate tx;
    Fixture fixture;
    record Fixture(long user, long account, long buy, long sell, long executed, long cancelled) {}

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tx = new TransactionTemplate(manager);
        fixture = tx.execute(status -> {
            var user = new User("계좌 삭제"); user.completeOnboarding(); em.persist(user);
            var account = new Account(user, "보존할 계좌", 1000000, true); em.persist(account);
            var now = Instant.now();
            var buy = Order.pendingLimit(account,"005930",Order.Side.BUY,1,10000,Order.Source.AI,"매수 근거",now);
            var sell = Order.pendingLimit(account,"005930",Order.Side.SELL,2,10000,Order.Source.AI,"매도 근거",now);
            var executed = Order.pendingMarket(account,"005930",Order.Side.BUY,1,"기존 근거",now);
            executed.execute();
            var cancelled = Order.pendingLimit(account,"005930",Order.Side.BUY,1,9000,Order.Source.AI,null,now);
            cancelled.cancel(now);
            em.persist(buy); em.persist(sell); em.persist(executed); em.persist(cancelled);
            em.persist(new Holding(account,"005930",5,BigDecimal.valueOf(50000)));
            em.persist(new Execution(executed,10000,1,null,null,now));
            return new Fixture(user.getId(),account.getId(),buy.getId(),sell.getId(),executed.getId(),cancelled.getId());
        });
    }

    // 삭제 전 자산과 체결/리포트는 유지하고 대기 매수·매도 주문만 취소한다. 중복 삭제도 성공해야 한다.
    @Test void deactivationCancelsPendingOrdersButKeepsAssetsAndHistory() throws Exception {
        deleteAccount(fixture.user(),fixture.account()).andExpect(status().isOk())
                .andExpect(content().json("{\"message\":\"delete_success\"}"));
        tx.executeWithoutResult(status -> {
            var account = em.find(Account.class,fixture.account());
            assertThat(account.isActive()).isFalse();
            assertThat(account.getCashBalance()).isEqualTo(1000000);
            assertThat(em.find(User.class,fixture.user()).isOnboardingCompleted()).isTrue();
            for (long id : new long[]{fixture.buy(),fixture.sell()}) {
                var order = em.find(Order.class,id);
                assertThat(order.getStatus()).isEqualTo(Order.Status.CANCELLED);
                assertThat(order.getReservedCash()).isZero();
                assertThat(order.getCancelledAt()).isNotNull();
            }
            assertThat(em.find(Order.class,fixture.executed()).getStatus()).isEqualTo(Order.Status.EXECUTED);
            assertThat(em.find(Order.class,fixture.executed()).getReport()).isNotNull();
            assertThat(em.find(Order.class,fixture.cancelled()).getStatus()).isEqualTo(Order.Status.CANCELLED);
            assertThat(em.createQuery("select h.quantity from Holding h where h.account.id=:id",Long.class)
                    .setParameter("id",fixture.account()).getSingleResult()).isEqualTo(5);
            assertThat(em.createQuery("select count(e) from Execution e where e.order.id=:id",Long.class)
                    .setParameter("id",fixture.executed()).getSingleResult()).isEqualTo(1);
        });
        mvc.perform(get("/api/v1/users/me/accounts").cookie(cookie(fixture.user())))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/v1/users/me/accounts/{id}",fixture.account()).cookie(cookie(fixture.user())))
                .andExpect(status().isNotFound());
        deleteAccount(fixture.user(),fixture.account()).andExpect(status().isOk());
        // 마지막 계좌 삭제 후에도 온보딩은 유지해 새 계좌를 생성할 수 있다. 비활성 계좌 이름은 재사용하지 않는다.
        assertThatThrownBy(() -> accountService.create(fixture.user(),
                new com.stock_spoon.river_be.account.dto.AccountCreateRequest("보존할 계좌",1000000L)))
                .isInstanceOfSatisfying(com.stock_spoon.river_be.account.exception.AccountException.class,
                        error -> assertThat(error.code()).isEqualTo("DUPLICATE_ACCOUNT_NAME"));
        var created=accountService.create(fixture.user(),
                new com.stock_spoon.river_be.account.dto.AccountCreateRequest("새 계좌",1000000L));
        assertThat(created.accountId()).isNotEqualTo(fixture.account());
    }

    // 대결 상태와 사용자/AI 역할에 관계없이 개별 삭제를 거절하며 상대 계좌와 대기 주문도 건드리지 않는다.
    @Test void duelAccountsCannotBeDeactivatedInAnyCompetitionState() throws Exception {
        long opponent = tx.execute(status -> {
            var user = em.find(User.class,fixture.user());
            var competition = new Competition(user,"대결",Competition.Type.USER_VS_AI,
                    LocalDate.now(),LocalDate.now().plusDays(1),1000000,1); em.persist(competition);
            em.persist(new CompetitionParticipant(competition,em.find(Account.class,fixture.account())));
            var other = new Account(user,"대결 사용자",1000000,false); em.persist(other);
            em.persist(new CompetitionParticipant(competition,other));
            return other.getId();
        });
        for (String state : new String[]{"READY","IN_PROGRESS","COMPLETED","CANCELLED"}) {
            tx.executeWithoutResult(s -> em.createNativeQuery("update competitions set state=:state where user_id=:id")
                    .setParameter("state",state).setParameter("id",fixture.user()).executeUpdate());
            for(long id : new long[]{fixture.account(),opponent}) deleteAccount(fixture.user(),id)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("COMPETITION_ACCOUNT_DELETE_NOT_ALLOWED"));
        }
        assertThat(accounts.findById(fixture.account()).orElseThrow().isActive()).isTrue();
        assertThat(accounts.findById(opponent).orElseThrow().isActive()).isTrue();
        assertThat(orders.findById(fixture.buy()).orElseThrow().getStatus()).isEqualTo(Order.Status.PENDING);
        verifyNoInteractions(queue);
    }

    @Test void ownershipMissingAccountAndSecurityAreChecked() throws Exception {
        long other = tx.execute(s -> { var u=new User("타 회원");em.persist(u);return u.getId(); });
        deleteAccount(other,fixture.account()).andExpect(status().isNotFound());
        deleteAccount(fixture.user(),Long.MAX_VALUE).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/users/me/accounts/{id}",fixture.account()).cookie(cookie(fixture.user())))
                .andExpect(status().isForbidden());
        var csrf = csrf();
        mvc.perform(delete("/api/v1/users/me/accounts/{id}",fixture.account())
                        .cookie(csrf.cookie()).header("X-XSRF-TOKEN",csrf.token()))
                .andExpect(status().isUnauthorized());
        assertThat(accounts.findById(fixture.account()).orElseThrow().isActive()).isTrue();
    }

    // 실제 SQL 취소를 먼저 반영한 뒤 오류를 주입해 계좌 변경과 취소가 함께 롤백되는지 확인한다.
    @Test void databaseFailureRollsBackCancellationAndDeactivation() throws Exception {
        doAnswer(call -> {
            em.find(Order.class,fixture.buy()).cancel(Instant.now()); em.flush();
            throw new IllegalStateException("rollback cancellation");
        }).when(orders).findAllByAccountIdAndStatus(fixture.account(),Order.Status.PENDING);
        assertThatThrownBy(() -> deleteAccount(fixture.user(),fixture.account()))
                .hasRootCauseMessage("rollback cancellation");
        assertThat(accounts.findById(fixture.account()).orElseThrow().isActive()).isTrue();
        assertThat(orders.findById(fixture.buy()).orElseThrow().getStatus()).isEqualTo(Order.Status.PENDING);
        verifyNoInteractions(queue);
    }

    // 커밋 후 정리 실패는 기존 reconcile에서 복구한다. 이미 완료된 비활성화를 실패로 돌려서는 안 된다.
    @Test void redisFailureAfterCommitDoesNotUndoSuccessfulDeactivation() throws Exception {
        when(queue.cleanupInactiveAccount(fixture.account())).thenThrow(new IllegalStateException("redis unavailable"));
        deleteAccount(fixture.user(),fixture.account()).andExpect(status().isOk());
        assertThat(accounts.findById(fixture.account()).orElseThrow().isActive()).isFalse();
    }

    org.springframework.test.web.servlet.ResultActions deleteAccount(long user,long account) throws Exception {
        var csrf=csrf();
        return mvc.perform(delete("/api/v1/users/me/accounts/{id}",account)
                .cookie(cookie(user),csrf.cookie()).header("X-XSRF-TOKEN",csrf.token()));
    }
    Cookie cookie(long user) { return new Cookie("access_token",tokens.issue(user).accessToken()); }
    record Csrf(Cookie cookie,String token) {}
    Csrf csrf() throws Exception {
        var response=mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
        return new Csrf(response.getCookie("XSRF-TOKEN"),com.jayway.jsonpath.JsonPath.read(response.getContentAsString(),"$.token"));
    }
}
