package com.stock_spoon.river_be.order.service;

import com.stock_spoon.river_be.execution.entity.Execution;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.execution.repository.ExecutionRepository;
import com.stock_spoon.river_be.order.repository.OrderRepository;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class OrderHistoryTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired OrderRepository orders;
    @Autowired ExecutionRepository executions;
    @Autowired JwtTokenProvider tokens;
    @Autowired EntityManager em;
    @MockitoBean KiwoomMarketClient market;
    private MockMvc mvc;
    private User user;
    private Account account;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        user = users.save(new User("체결목록 사용자"));
        account = accounts.save(new Account(user, "체결목록", 1000000));
        when(market.stockDetails(anyString())).thenAnswer(call ->
                new KiwoomMarketClient.StockDetails(call.getArgument(0), "삼성전자", "전기전자"));
    }

    private Order pending(Account owner, Order.Side side, String created) {
        return orders.save(Order.pendingLimit(owner, "005930", side, 2, 70000,
                Order.Source.AI, side == Order.Side.BUY ? "매수 실제 근거 원문" : "매도 실제 근거 원문", Instant.parse(created)));
    }

    private Order executed(String created, String at, Order.Side side) {
        var order = pending(account, side, created);
        executions.save(new Execution(order, 69500, 2,
                side == Order.Side.SELL ? new BigDecimal("1000.12") : null,
                side == Order.Side.SELL ? new BigDecimal("0.7247") : null, Instant.parse(at)));
        order.execute();
        return order;
    }

    private ResultActions list(String query) throws Exception {
        return mvc.perform(get("/api/v1/accounts/{id}/orders" + query, account.getId())
                .cookie(new Cookie("access_token", tokens.issue(user.getId()).accessToken())));
    }

    private ResultActions listExecuted(String query) throws Exception {
        return list(query + (query.isEmpty() ? "?" : "&") + "order_status=executed");
    }

    @Test void limitsAfterExecutionTimeAndIdSortAndExcludesOtherStatesAndAccounts() throws Exception {
        var oldest = executed("2026-09-05T00:00:00Z", "2026-09-01T01:00:00Z", Order.Side.BUY);
        var middle = executed("2026-08-01T00:00:00Z", "2026-09-02T01:00:00Z", Order.Side.SELL);
        var tie1 = executed("2026-08-01T00:00:00Z", "2026-09-03T01:00:00Z", Order.Side.BUY);
        var tie2 = executed("2026-08-01T00:00:00Z", "2026-09-03T01:00:00Z", Order.Side.BUY);
        pending(account, Order.Side.BUY, "2026-09-04T00:00:00Z");
        pending(account, Order.Side.BUY, "2026-09-04T00:00:00Z").cancel(Instant.now());
        var other = accounts.save(new Account(users.save(new User("다른사용자")), "다른계좌", 1000000));
        pending(other, Order.Side.BUY, "2026-09-04T00:00:00Z").execute();
        listExecuted("?limit=3").andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(3))
                .andExpect(jsonPath("$.orders[0].order_id").value(tie2.getId()))
                .andExpect(jsonPath("$.orders[1].order_id").value(tie1.getId()))
                .andExpect(jsonPath("$.orders[2].order_id").value(middle.getId()));
        verify(market, times(1)).stockDetails("005930");
        listExecuted("").andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(4))
                .andExpect(jsonPath("$.orders[3].order_id").value(oldest.getId()));
        listExecuted("?limit=10").andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(4));
    }

    @Test void matchesDocumentShapeAndPreservesSellPrecisionAndBuyNulls() throws Exception {
        executed("2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z", Order.Side.BUY);
        executed("2026-09-02T00:00:00Z", "2026-09-02T01:00:00Z", Order.Side.SELL);
        em.flush();
        em.clear();
        String body = list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].stock_name").value("삼성전자"))
                .andExpect(jsonPath("$.orders[0].created_at").value("2026-09-02T09:00:00+09:00"))
                .andExpect(jsonPath("$.orders[0].order_status").value("executed"))
                .andExpect(jsonPath("$.orders[0].reserved_cash").value(0))
                .andExpect(jsonPath("$.orders[0].canceled_at").isEmpty())
                .andExpect(jsonPath("$.orders[0].can_cancel").value(false))
                .andExpect(jsonPath("$.orders[0].reason.summary").value("매도 실제 근거 원문"))
                .andExpect(jsonPath("$.orders[1].reason.summary").value("매수 실제 근거 원문"))
                .andExpect(jsonPath("$.orders[0].executions.length()").value(1))
                .andExpect(jsonPath("$.orders[0].execution_summary.total_amount").value(139000))
                .andExpect(jsonPath("$.orders[0].execution_summary.realized_pnl").value(1000.12))
                .andExpect(jsonPath("$.orders[0].execution_summary.realized_return_percent").value(0.7247))
                .andExpect(jsonPath("$.orders[1].execution_summary.realized_pnl").isEmpty())
                .andExpect(jsonPath("$.orders[1].executions[0].realized_return_percent").isEmpty())
                .andReturn().getResponse().getContentAsString();
        var tree = JsonMapper.builder().build().readTree(body);
        assertThat(tree.propertyNames()).containsExactlyInAnyOrder("account_id", "orders");
        var item = tree.path("orders").get(0);
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("order_id", "stock_code", "stock_name",
                "order_source", "order_side", "order_type", "order_status", "quantity", "limit_price",
                "reserved_cash", "created_at", "canceled_at", "reason", "executions", "execution_summary", "can_cancel");
        assertThat(item.path("reason").propertyNames()).containsExactly("summary");
        assertThat(item.path("execution_summary").propertyNames()).containsExactlyInAnyOrder("quantity", "average_price",
                "total_amount", "executed_at", "realized_pnl", "realized_return_percent");
        assertThat(item.path("executions").get(0).propertyNames()).containsExactlyInAnyOrder("execution_id",
                "execution_price", "execution_quantity", "realized_pnl", "realized_return_percent", "created_at");
    }

    @Test void returnsExplicitNullSummaryWhenReportIsMissingForBuyAndSell() throws Exception {
        for (var side : Order.Side.values()) {
            var order = orders.save(Order.pendingLimit(account, "005930", side, 2, 70000,
                    Order.Source.AI, null, Instant.parse("2026-09-01T00:00:00Z")));
            executions.save(new Execution(order, 69500, 2,
                    side == Order.Side.SELL ? BigDecimal.ZERO : null,
                    side == Order.Side.SELL ? BigDecimal.ZERO : null, Instant.parse("2026-09-01T01:00:00Z")));
            order.execute();
        }
        em.flush();
        em.clear();
        String body = list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        var tree = JsonMapper.builder().build().readTree(body);
        for (var item : tree.path("orders")) {
            assertThat(item.path("reason").propertyNames()).containsExactly("summary");
            assertThat(item.path("reason").path("summary").isNull()).isTrue();
        }
    }
    @Test void repositorySupportsOptionalStatusInBothHistoryAndCountQueries() {
        var pending = pending(account, Order.Side.BUY, "2026-09-01T00:00:00Z");
        var executed = executed("2026-09-02T00:00:00Z", "2026-09-02T01:00:00Z", Order.Side.BUY);
        var cancelled = pending(account, Order.Side.SELL, "2026-09-03T00:00:00Z");
        cancelled.cancel(Instant.parse("2026-09-03T01:00:00Z"));
        var all = orders.findHistory(account.getId(), null, null, null,
                org.springframework.data.domain.Pageable.unpaged());
        assertThat(all).extracting(Order::getId)
                .containsExactlyInAnyOrder(pending.getId(), executed.getId(), cancelled.getId());
        assertThat(orders.countHistory(account.getId(), null, null, null)).isEqualTo(3);
        for (var order : List.of(pending, executed, cancelled)) {
            assertThat(orders.findHistory(account.getId(), order.getStatus(), null, null,
                    org.springframework.data.domain.Pageable.unpaged()))
                    .extracting(Order::getId).containsExactly(order.getId());
            assertThat(orders.countHistory(account.getId(), order.getStatus(), null, null)).isEqualTo(1);
        }
        verifyNoInteractions(market);
    }

    @Test void repositoryCombinesStatusSideAccountAcrossSourcesBeforePaging() {
        var buy1 = pending(account, Order.Side.BUY, "2026-09-01T00:00:00Z");
        var buy2 = pending(account, Order.Side.BUY, "2026-09-02T00:00:00Z");
        pending(account, Order.Side.SELL, "2026-09-03T00:00:00Z");
        executed("2026-09-04T00:00:00Z", "2026-09-04T01:00:00Z", Order.Side.BUY);
        var other = accounts.save(new Account(users.save(new User("Repository 타인")), "타인 계좌", 1000000));
        pending(other, Order.Side.BUY, "2026-09-05T00:00:00Z");
        var manual = orders.save(Order.pendingLimit(account, "005930", Order.Side.BUY, 2, 70000,
                Order.Source.USER, null, Instant.parse("2026-09-05T00:00:00Z")));
        assertThat(orders.countHistory(account.getId(), Order.Status.PENDING, Order.Side.BUY, null))
                .isEqualTo(3);
        var first = orders.findHistory(account.getId(), Order.Status.PENDING, Order.Side.BUY, null,
                org.springframework.data.domain.PageRequest.of(0, 1));
        var second = orders.findHistory(account.getId(), Order.Status.PENDING, Order.Side.BUY, null,
                org.springframework.data.domain.PageRequest.of(1, 1));
        var third = orders.findHistory(account.getId(), Order.Status.PENDING, Order.Side.BUY, null,
                org.springframework.data.domain.PageRequest.of(2, 1));
        assertThat(first).extracting(Order::getId).containsExactly(manual.getId());
        assertThat(second).extracting(Order::getId).containsExactly(buy2.getId());
        assertThat(third).extracting(Order::getId).containsExactly(buy1.getId());
        assertThat(orders.countHistory(account.getId(), null, Order.Side.BUY, null)).isEqualTo(4);
        assertThat(orders.findHistory(account.getId(), null, Order.Side.BUY, null,
                org.springframework.data.domain.Pageable.unpaged())).hasSize(4);
        assertThat(orders.countHistory(account.getId(), Order.Status.CANCELLED, Order.Side.SELL, null))
                .isZero();
        assertThat(orders.findHistory(account.getId(), Order.Status.CANCELLED, Order.Side.SELL, null,
                org.springframework.data.domain.Pageable.unpaged())).isEmpty();
        verifyNoInteractions(market);
    }

    @Test void filtersPendingAndCancelledOrdersAndBuildsEmptyExecutionResponses() throws Exception {
        var newest = pending(account, Order.Side.BUY, "2026-09-05T00:00:00Z");
        var older = pending(account, Order.Side.BUY, "2026-09-04T00:00:00Z");
        var cancelled = pending(account, Order.Side.SELL, "2026-09-06T00:00:00Z");
        cancelled.cancel(Instant.parse("2026-09-06T01:00:00Z"));
        executed("2026-09-03T00:00:00Z", "2026-09-09T01:00:00Z", Order.Side.BUY);
        list("?page=0&limit=1&order_status=pending&order_side=buy").andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.total_elements").value(2))
                .andExpect(jsonPath("$.pagination.total_pages").value(2))
                .andExpect(jsonPath("$.pagination.has_next").value(true))
                .andExpect(jsonPath("$.orders[0].order_id").value(newest.getId()))
                .andExpect(jsonPath("$.orders[0].order_status").value("pending"))
                .andExpect(jsonPath("$.orders[0].reserved_cash").value(140000))
                .andExpect(jsonPath("$.orders[0].executions").isEmpty())
                .andExpect(jsonPath("$.orders[0].execution_summary").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.orders[0].can_cancel").value(true));
        list("?page=1&limit=1&order_status=pending&order_side=buy").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].order_id").value(older.getId()))
                .andExpect(jsonPath("$.pagination.has_next").value(false));
        list("?page=0&order_status=cancelled&order_side=sell").andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.total_elements").value(1))
                .andExpect(jsonPath("$.orders[0].order_id").value(cancelled.getId()))
                .andExpect(jsonPath("$.orders[0].order_status").value("cancelled"))
                .andExpect(jsonPath("$.orders[0].reserved_cash").value(0))
                .andExpect(jsonPath("$.orders[0].canceled_at").value("2026-09-06T10:00:00+09:00"))
                .andExpect(jsonPath("$.orders[0].executions").isEmpty())
                .andExpect(jsonPath("$.orders[0].execution_summary").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.orders[0].can_cancel").value(false));
        list("?page=0&order_status=pending&order_side=sell").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").isEmpty())
                .andExpect(jsonPath("$.pagination.total_elements").value(0));
    }

    @Test void omittedStatusIncludesEveryStateAndSortsByCreationTimeWithIdTieBreak() throws Exception {
        var filled = executed("2026-09-01T00:00:00Z", "2026-09-09T01:00:00Z", Order.Side.BUY);
        var newest = pending(account, Order.Side.BUY, "2026-09-05T00:00:00Z");
        var cancelled = pending(account, Order.Side.SELL, "2026-09-05T00:00:00Z");
        cancelled.cancel(Instant.parse("2026-09-05T01:00:00Z"));
        var other = accounts.save(new Account(users.save(new User("상태 필터 타인")), "타인", 1000000));
        pending(other, Order.Side.BUY, "2026-09-10T00:00:00Z");
        var manual = orders.save(Order.pendingLimit(account, "005930", Order.Side.BUY, 2, 70000,
                Order.Source.USER, null, Instant.parse("2026-09-10T00:00:00Z")));
        list("?page=0&limit=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.total_elements").value(4))
                .andExpect(jsonPath("$.orders[0].order_id").value(manual.getId()))
                .andExpect(jsonPath("$.orders[1].order_id").value(cancelled.getId()));
        list("?page=1&limit=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].order_id").value(newest.getId()))
                .andExpect(jsonPath("$.orders[1].order_id").value(filled.getId()))
                .andExpect(jsonPath("$.pagination.has_next").value(false));
        list("?limit=3").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(3))
                .andExpect(jsonPath("$.orders[0].order_status").value("pending"))
                .andExpect(jsonPath("$.pagination").doesNotExist());
    }

    @Test void keepsRecordedExecutionsForPendingAndCancelledOrders() throws Exception {
        for (var state : List.of(Order.Status.PENDING, Order.Status.CANCELLED)) {
            var order = pending(account, Order.Side.BUY, "2026-09-01T00:00:00Z");
            executions.save(new Execution(order, 69500, 1, null, null, Instant.parse("2026-09-01T01:00:00Z")));
            if (state == Order.Status.CANCELLED) order.cancel(Instant.parse("2026-09-01T02:00:00Z"));
            list("?page=0&order_status=" + state.name().toLowerCase(java.util.Locale.ROOT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orders[0].order_id").value(order.getId()))
                    .andExpect(jsonPath("$.orders[0].executions.length()").value(1))
                    .andExpect(jsonPath("$.orders[0].execution_summary.quantity").value(1))
                    .andExpect(jsonPath("$.orders[0].execution_summary.total_amount").value(69500));
        }
    }

    @Test void validatesOrderStatusBeforeQueryingMarketData() throws Exception {
        for (String value : List.of("", "all", "unknown", "PENDING", "canceled")) {
            list("?page=0&order_status=" + value).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ORDER_QUERY"));
        }
        for (String value : List.of("pending", "executed", "cancelled")) {
            list("?page=0&order_status=" + value).andExpect(status().isOk())
                    .andExpect(jsonPath("$.orders").isEmpty());
        }
        verifyNoInteractions(market);
    }

    @Test void rejectsBadLimitAndReturnsEmptyWithoutExternalCalls() throws Exception {
        list("").andExpect(status().isOk()).andExpect(jsonPath("$.orders").isEmpty());
        for (String value : List.of("0", "-1", "abc", "1.5", "2147483648", "")) {
            list("?limit=" + value).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ORDER_QUERY"));
        }
        verifyNoInteractions(market);
    }

    @Test void rejectsUnauthenticatedForeignMissingAndInactiveAccounts() throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}/orders", account.getId())).andExpect(status().isUnauthorized());
        var other = users.save(new User("타인"));
        mvc.perform(get("/api/v1/accounts/{id}/orders", account.getId())
                .cookie(new Cookie("access_token", tokens.issue(other.getId()).accessToken())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mvc.perform(get("/api/v1/accounts/{id}/orders", Long.MAX_VALUE)
                .cookie(new Cookie("access_token", tokens.issue(user.getId()).accessToken())))
                .andExpect(status().isNotFound());
        em.flush();
        em.createNativeQuery("update accounts set is_active=false where account_id=:id")
                .setParameter("id", account.getId()).executeUpdate();
        em.clear();
        list("").andExpect(status().isNotFound());
        verifyNoInteractions(market);
    }

    @Test void rejectsZeroExecutionsEvenWithLimit() throws Exception {
        var invalid = pending(account, Order.Side.BUY, "2026-09-01T00:00:00Z");
        invalid.execute();
        list("?limit=3").andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_DATA"));
        verifyNoInteractions(market);
    }

    @Test void pagesAfterSideFilterAndCountsOrdersRatherThanFills() throws Exception {
        var oldest = executed("2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z", Order.Side.BUY);
        var tie1 = executed("2026-09-02T00:00:00Z", "2026-09-03T01:00:00Z", Order.Side.BUY);
        var tie2 = executed("2026-09-02T00:00:00Z", "2026-09-03T01:00:00Z", Order.Side.BUY);
        executed("2026-09-04T00:00:00Z", "2026-09-04T01:00:00Z", Order.Side.SELL);
        pending(account, Order.Side.BUY, "2026-09-05T00:00:00Z");
        var manual = orders.save(Order.pendingLimit(account, "005930", Order.Side.BUY, 2, 70000,
                Order.Source.USER, null, Instant.parse("2026-09-05T00:00:00Z")));
        executions.save(new Execution(manual, 69500, 2, null, null, Instant.parse("2026-09-05T01:00:00Z")));
        manual.execute();
        listExecuted("?page=0&limit=2&order_side=buy").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].order_id").value(manual.getId()))
                .andExpect(jsonPath("$.orders[1].order_id").value(tie2.getId()))
                .andExpect(jsonPath("$.pagination.total_elements").value(4))
                .andExpect(jsonPath("$.pagination.total_pages").value(2))
                .andExpect(jsonPath("$.pagination.has_next").value(true));
        listExecuted("?page=1&limit=2&order_side=buy").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].order_id").value(tie1.getId()))
                .andExpect(jsonPath("$.orders[1].order_id").value(oldest.getId()))
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.has_next").value(false));
        list("?order_side=sell&limit=3").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].order_side").value("sell"))
                .andExpect(jsonPath("$.pagination").doesNotExist());
        listExecuted("?page=0&limit=10").andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.total_elements").value(5));
    }

    @Test void aggregatesMultipleExecutionsAndSortsByLastFill() throws Exception {
        executed("2026-09-02T00:00:00Z", "2026-09-02T01:00:00Z", Order.Side.BUY);
        var sell = pending(account, Order.Side.SELL, "2026-09-01T00:00:00Z");
        executions.save(new Execution(sell, 100, 1, new BigDecimal("10"), new BigDecimal("11.1111"),
                Instant.parse("2026-09-01T01:00:00Z")));
        executions.save(new Execution(sell, 121, 1, new BigDecimal("21"), new BigDecimal("21"),
                Instant.parse("2026-09-03T01:00:00Z")));
        sell.execute();
        listExecuted("?page=0&limit=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.total_elements").value(2))
                .andExpect(jsonPath("$.orders[0].order_id").value(sell.getId()))
                .andExpect(jsonPath("$.orders[0].executions.length()").value(2))
                .andExpect(jsonPath("$.orders[0].execution_summary.quantity").value(2))
                .andExpect(jsonPath("$.orders[0].execution_summary.total_amount").value(221))
                .andExpect(jsonPath("$.orders[0].execution_summary.average_price").value(110.5))
                .andExpect(jsonPath("$.orders[0].execution_summary.realized_pnl").value(31))
                .andExpect(jsonPath("$.orders[0].execution_summary.realized_return_percent").value(16.3158))
                .andExpect(jsonPath("$.orders[0].execution_summary.executed_at").value("2026-09-03T10:00:00+09:00"));
        listExecuted("?limit=3").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].executions.length()").value(2))
                .andExpect(jsonPath("$.pagination").doesNotExist());
    }

    @Test void validatesPageQueriesAndReturnsEmptyPagesWithoutMarketCalls() throws Exception {
        for (String query : List.of("page=-1", "page=abc", "page=", "page=1.5", "page=2147483648",
                "page=0&limit=101", "page=0&limit=0", "page=0&limit=abc", "order_side=all", "order_side=")) {
            list("?" + query).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ORDER_QUERY"));
        }
        list("?page=0").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").isEmpty())
                .andExpect(jsonPath("$.pagination.limit").value(10))
                .andExpect(jsonPath("$.pagination.total_pages").value(0))
                .andExpect(jsonPath("$.pagination.has_next").value(false));
        verifyNoInteractions(market);
        executed("2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z", Order.Side.BUY);
        list("?page=2147483647&limit=100").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").isEmpty())
                .andExpect(jsonPath("$.pagination.total_elements").value(1));
        list("?page=0&order_side=sell").andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.total_elements").value(0));
        verifyNoInteractions(market);
    }

    @Test void defaultPageReturnsTenAndBuySummaryPreservesNullsAndFractionalAverage() throws Exception {
        for (int index = 0; index < 11; index++) {
            executed("2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z", Order.Side.BUY);
        }
        list("?page=0").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(10))
                .andExpect(jsonPath("$.pagination.total_elements").value(11))
                .andExpect(jsonPath("$.pagination.total_pages").value(2));
        var buy = pending(account, Order.Side.BUY, "2026-09-02T00:00:00Z");
        executions.save(new Execution(buy, 100, 1, null, null, Instant.parse("2026-09-02T01:00:00Z")));
        executions.save(new Execution(buy, 101, 1, null, null, Instant.parse("2026-09-02T01:00:01Z")));
        buy.execute();
        list("?page=0&limit=1&order_side=buy").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].executions.length()").value(2))
                .andExpect(jsonPath("$.orders[0].execution_summary.average_price").value(100.5))
                .andExpect(jsonPath("$.orders[0].execution_summary.realized_pnl").isEmpty())
                .andExpect(jsonPath("$.orders[0].execution_summary.realized_return_percent").isEmpty());
    }

    @Test void doesNotPresentPartialSellPnlAsCompleteSummary() throws Exception {
        var sell = pending(account, Order.Side.SELL, "2026-09-01T00:00:00Z");
        executions.save(new Execution(sell, 100, 1, new BigDecimal("10"), new BigDecimal("11.1111"),
                Instant.parse("2026-09-01T01:00:00Z")));
        executions.save(new Execution(sell, 121, 1, null, null, Instant.parse("2026-09-01T01:00:01Z")));
        sell.execute();
        list("?page=0&order_side=sell").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].execution_summary.total_amount").value(221))
                .andExpect(jsonPath("$.orders[0].execution_summary.realized_pnl").isEmpty())
                .andExpect(jsonPath("$.orders[0].execution_summary.realized_return_percent").isEmpty());
    }

    @Test void pagedQueriesCheckOwnershipAndOnlyResolveSelectedStockNames() throws Exception {
        executed("2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z", Order.Side.BUY);
        var latest = orders.save(Order.pendingLimit(account, "000660", Order.Side.BUY, 2, 100,
                Order.Source.AI, null, Instant.parse("2026-09-02T00:00:00Z")));
        executions.save(new Execution(latest, 100, 2, null, null, Instant.parse("2026-09-02T01:00:00Z")));
        latest.execute();
        var other = users.save(new User("페이지 타인"));
        mvc.perform(get("/api/v1/accounts/{id}/orders?page=0", account.getId())
                .cookie(new Cookie("access_token", tokens.issue(other.getId()).accessToken())))
                .andExpect(status().isNotFound());
        verifyNoInteractions(market);
        list("?page=0&limit=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].stock_code").value("000660"));
        verify(market).stockDetails("000660");
        verify(market, never()).stockDetails("005930");
    }

    @Test void stockFilterCombinesWithStatusAndSideBeforePaging() throws Exception {
        var first = pending(account, Order.Side.BUY, "2026-09-01T00:00:00Z");
        var second = pending(account, Order.Side.BUY, "2026-09-02T00:00:00Z");
        pending(account, Order.Side.SELL, "2026-09-03T00:00:00Z");
        executed("2026-09-04T00:00:00Z", "2026-09-04T01:00:00Z", Order.Side.BUY);
        orders.save(Order.pendingLimit(account, "000660", Order.Side.BUY, 2, 100,
                Order.Source.AI, null, Instant.parse("2026-09-05T00:00:00Z")));
        String filter = "&limit=1&stock_code=005930&order_status=pending&order_side=buy";
        list("?page=0" + filter).andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].order_id").value(second.getId()))
                .andExpect(jsonPath("$.orders[0].stock_code").value("005930"))
                .andExpect(jsonPath("$.pagination.page").value(0))
                .andExpect(jsonPath("$.pagination.limit").value(1))
                .andExpect(jsonPath("$.pagination.total_elements").value(2))
                .andExpect(jsonPath("$.pagination.total_pages").value(2))
                .andExpect(jsonPath("$.pagination.has_next").value(true));
        list("?page=1" + filter).andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].order_id").value(first.getId()))
                .andExpect(jsonPath("$.pagination.has_next").value(false));
        list("?page=2" + filter).andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").isEmpty())
                .andExpect(jsonPath("$.pagination.total_elements").value(2));
        verify(market, never()).stockDetails("000660");
    }

    @Test void stockFilterWorksWithoutPagingAndOmissionKeepsAllStocks() throws Exception {
        pending(account, Order.Side.BUY, "2026-09-01T00:00:00Z");
        orders.save(Order.pendingLimit(account, "000660", Order.Side.BUY, 2, 100,
                Order.Source.AI, null, Instant.parse("2026-09-02T00:00:00Z")));
        list("?stock_code=005930&limit=3").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].stock_code").value("005930"))
                .andExpect(jsonPath("$.pagination").doesNotExist());
        list("?page=0").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.pagination.total_elements").value(2));
    }

    @Test void stockFilterReturnsEmptyForUnknownCodeAndRejectsBlankValues() throws Exception {
        pending(account, Order.Side.BUY, "2026-09-01T00:00:00Z");
        list("?page=0&stock_code=999999").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").isEmpty())
                .andExpect(jsonPath("$.pagination.total_elements").value(0))
                .andExpect(jsonPath("$.pagination.total_pages").value(0))
                .andExpect(jsonPath("$.pagination.has_next").value(false));
        list("?stock_code=00593").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").isEmpty());
        for (String code : List.of("", " ", "\t")) {
            mvc.perform(get("/api/v1/accounts/{id}/orders", account.getId())
                    .param("stock_code", code)
                    .cookie(new Cookie("access_token", tokens.issue(user.getId()).accessToken())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ORDER_QUERY"));
        }
        verifyNoInteractions(market);
    }

    @Test void listsBothSourcesForEitherAccountTypeWithConsistentPaginationAndFilters() throws Exception {
        for (boolean managed : List.of(true, false)) {
            account = accounts.save(new Account(user, "혼합 출처 " + managed, 1000000, managed));
            var ai = pending(account, Order.Side.BUY, "2026-09-01T00:00:00Z");
            var manual = orders.save(Order.pendingLimit(account, "005930", Order.Side.BUY, 2, 70000,
                    Order.Source.USER, null, Instant.parse("2026-09-02T00:00:00Z")));
            pending(account, Order.Side.SELL, "2026-09-03T00:00:00Z");
            pending(account, Order.Side.BUY, "2026-09-04T00:00:00Z").cancel(Instant.now());
            orders.save(Order.pendingLimit(account, "000660", Order.Side.BUY, 2, 70000,
                    Order.Source.USER, null, Instant.parse("2026-09-05T00:00:00Z")));
            String filter = "?order_status=pending&order_side=buy&stock_code=005930";
            list(filter).andExpect(status().isOk())
                    .andExpect(jsonPath("$.orders.length()").value(2))
                    .andExpect(jsonPath("$.orders[0].order_id").value(manual.getId()))
                    .andExpect(jsonPath("$.orders[0].order_source").value("USER"))
                    .andExpect(jsonPath("$.orders[1].order_id").value(ai.getId()))
                    .andExpect(jsonPath("$.orders[1].order_source").value("AI"));
            list(filter + "&page=0&limit=1").andExpect(status().isOk())
                    .andExpect(jsonPath("$.orders[0].order_id").value(manual.getId()))
                    .andExpect(jsonPath("$.pagination.total_elements").value(2))
                    .andExpect(jsonPath("$.pagination.total_pages").value(2))
                    .andExpect(jsonPath("$.pagination.has_next").value(true));
            list(filter + "&page=1&limit=1").andExpect(status().isOk())
                    .andExpect(jsonPath("$.orders[0].order_id").value(ai.getId()))
                    .andExpect(jsonPath("$.pagination.total_elements").value(2))
                    .andExpect(jsonPath("$.pagination.has_next").value(false));
            list(filter + "&page=2&limit=1").andExpect(status().isOk())
                    .andExpect(jsonPath("$.orders").isEmpty())
                    .andExpect(jsonPath("$.pagination.total_elements").value(2));
        }
    }

    @Test void returnsEverySourceAndStateForBothAccountTypes() throws Exception {
        for (boolean managed : List.of(true, false)) {
            account = accounts.save(new Account(user, "전체 조합 " + managed, 1000000, managed));
            var expected = new java.util.ArrayList<Order>();
            for (var source : Order.Source.values()) {
                for (var state : Order.Status.values()) {
                    var order = orders.save(Order.pendingLimit(account, "005930", Order.Side.BUY, 2, 70000,
                            source, null, Instant.parse("2026-09-01T00:00:00Z")));
                    if (state == Order.Status.EXECUTED) {
                        executions.save(new Execution(order, 69500, 2, null, null,
                                Instant.parse("2026-09-01T01:00:00Z")));
                        order.execute();
                    } else if (state == Order.Status.CANCELLED) {
                        order.cancel(Instant.parse("2026-09-01T02:00:00Z"));
                    }
                    expected.add(order);
                }
            }
            em.flush();
            for (String query : List.of("", "?page=0&limit=10")) {
                var response = list(query).andExpect(status().isOk())
                        .andExpect(jsonPath("$.orders.length()").value(6));
                if (!query.isEmpty()) response.andExpect(jsonPath("$.pagination.total_elements").value(6));
                var tree = JsonMapper.builder().build().readTree(response.andReturn().getResponse().getContentAsString());
                var actual = new java.util.HashMap<Long, String>();
                for (var row : tree.path("orders")) actual.put(row.path("order_id").asLong(),
                        row.path("order_source").asText() + ":" + row.path("order_status").asText());
                assertThat(actual).hasSize(6);
                for (var order : expected) assertThat(actual).containsEntry(order.getId(),
                        order.getSource().name() + ":" + order.getStatus().name().toLowerCase(java.util.Locale.ROOT));
            }
            for (var state : Order.Status.values()) {
                list("?page=0&order_status=" + state.name().toLowerCase(java.util.Locale.ROOT))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.orders.length()").value(2))
                        .andExpect(jsonPath("$.pagination.total_elements").value(2));
            }
        }
    }

    @Test void mapsStockInfoFailureTo503() throws Exception {
        executed("2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z", Order.Side.BUY);
        when(market.stockDetails("005930")).thenThrow(new IllegalStateException("provider failure"));
        list("").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ORDER_DATA_UNAVAILABLE"));
    }
}
