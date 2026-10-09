package com.stock_spoon.river_be.order;

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

    @Test void limitsAfterExecutionTimeAndIdSortAndExcludesOtherStatesAndAccounts() throws Exception {
        var oldest = executed("2026-09-05T00:00:00Z", "2026-09-01T01:00:00Z", Order.Side.BUY);
        var middle = executed("2026-08-01T00:00:00Z", "2026-09-02T01:00:00Z", Order.Side.SELL);
        var tie1 = executed("2026-08-01T00:00:00Z", "2026-09-03T01:00:00Z", Order.Side.BUY);
        var tie2 = executed("2026-08-01T00:00:00Z", "2026-09-03T01:00:00Z", Order.Side.BUY);
        pending(account, Order.Side.BUY, "2026-09-04T00:00:00Z");
        pending(account, Order.Side.BUY, "2026-09-04T00:00:00Z").cancel(Instant.now());
        var other = accounts.save(new Account(users.save(new User("다른사용자")), "다른계좌", 1000000));
        pending(other, Order.Side.BUY, "2026-09-04T00:00:00Z").execute();
        list("?limit=3").andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(3))
                .andExpect(jsonPath("$.orders[0].order_id").value(tie2.getId()))
                .andExpect(jsonPath("$.orders[1].order_id").value(tie1.getId()))
                .andExpect(jsonPath("$.orders[2].order_id").value(middle.getId()));
        verify(market, times(1)).stockDetails("005930");
        list("").andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(4))
                .andExpect(jsonPath("$.orders[3].order_id").value(oldest.getId()));
        list("?limit=10").andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(4));
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
        list("?page=0&limit=2&order_side=buy").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].order_id").value(tie2.getId()))
                .andExpect(jsonPath("$.orders[1].order_id").value(tie1.getId()))
                .andExpect(jsonPath("$.pagination.total_elements").value(3))
                .andExpect(jsonPath("$.pagination.total_pages").value(2))
                .andExpect(jsonPath("$.pagination.has_next").value(true));
        list("?page=1&limit=2&order_side=buy").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].order_id").value(oldest.getId()))
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.has_next").value(false));
        list("?order_side=sell&limit=3").andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].order_side").value("sell"))
                .andExpect(jsonPath("$.pagination").doesNotExist());
        list("?page=0&limit=10").andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.total_elements").value(4));
    }

    @Test void aggregatesMultipleExecutionsAndSortsByLastFill() throws Exception {
        executed("2026-09-02T00:00:00Z", "2026-09-02T01:00:00Z", Order.Side.BUY);
        var sell = pending(account, Order.Side.SELL, "2026-09-01T00:00:00Z");
        executions.save(new Execution(sell, 100, 1, new BigDecimal("10"), new BigDecimal("11.1111"),
                Instant.parse("2026-09-01T01:00:00Z")));
        executions.save(new Execution(sell, 121, 1, new BigDecimal("21"), new BigDecimal("21"),
                Instant.parse("2026-09-03T01:00:00Z")));
        sell.execute();
        list("?page=0&limit=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.total_elements").value(2))
                .andExpect(jsonPath("$.orders[0].order_id").value(sell.getId()))
                .andExpect(jsonPath("$.orders[0].executions.length()").value(2))
                .andExpect(jsonPath("$.orders[0].execution_summary.quantity").value(2))
                .andExpect(jsonPath("$.orders[0].execution_summary.total_amount").value(221))
                .andExpect(jsonPath("$.orders[0].execution_summary.average_price").value(110.5))
                .andExpect(jsonPath("$.orders[0].execution_summary.realized_pnl").value(31))
                .andExpect(jsonPath("$.orders[0].execution_summary.realized_return_percent").value(16.3158))
                .andExpect(jsonPath("$.orders[0].execution_summary.executed_at").value("2026-09-03T10:00:00+09:00"));
        list("?limit=3").andExpect(status().isOk())
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

    @Test void mapsStockInfoFailureTo503() throws Exception {
        executed("2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z", Order.Side.BUY);
        when(market.stockDetails("005930")).thenThrow(new IllegalStateException("provider failure"));
        list("").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ORDER_DATA_UNAVAILABLE"));
    }
}
