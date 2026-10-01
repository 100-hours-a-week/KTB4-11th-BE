package com.stock_spoon.river_be.account.service;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.account.exception.AccountException;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.order.*;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class AccountHoldingsTests {
    @Autowired AccountHoldingsService service;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired HoldingRepository holdings;
    @Autowired OrderRepository orders;
    @Autowired ExecutionRepository executions;
    @Autowired EntityManager em;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired com.stock_spoon.river_be.auth.token.JwtTokenProvider tokens;
    @MockitoBean KiwoomMarketClient market;
    private User user;
    private Account account;

    @BeforeEach void setup() {
        user = users.save(new User("보유 테스트"));
        account = accounts.save(new Account(user, "보유 계좌", 1000000));
    }

    @Test void evaluatesAllCandidatesBeforeLimitingAndSupportsBothDirections() {
        add("000001", 1, "100.00", 150, "2026-09-01T00:00:00Z");
        add("000002", 1, "100.00", 90, "2026-09-02T00:00:00Z");
        add("000003", 1, "100.00", 200, "2026-09-03T00:00:00Z");
        add("000004", 4, "892000.00", 221500, "2026-09-04T00:00:00Z");
        assertCodes("market_value", "desc", "3", "000004", "000003", "000001");
        assertCodes("market_value", "asc", "3", "000002", "000001", "000003");
        assertCodes("return_rate", "desc", "3", "000003", "000001", "000004");
        assertCodes("return_rate", "asc", "3", "000002", "000004", "000001");
        assertCodes("latest_purchase", "desc", "3", "000004", "000003", "000002");
        assertCodes("latest_purchase", "asc", null, "000001", "000002", "000003", "000004");
        var item = service.list(user.getId(), account.getId(), "market_value", "desc", "1").holdings().getFirst();
        assertThat(item.averagePurchasePrice()).isEqualByComparingTo("223000.00");
        assertThat(item.totalValue()).isEqualTo(886000);
        assertThat(item.unrealizedPnl()).isEqualByComparingTo("-6000.00");
        assertThat(item.returnPercent()).isEqualByComparingTo("-0.6726");
        assertThat(item.sector()).isEqualTo("전기·전자");
    }

    @Test void ignoresSellAndPendingOrderTimesAndPlacesMissingBuyTimesLast() {
        add("000002", 1, "100", 100, "2026-09-01T00:00:00Z");
        add("000001", 1, "100", 100, "2026-09-01T00:00:00Z");
        add("000003", 1, "100", 100, null);
        var sell = orders.save(Order.pendingLimit(account, "000002", Order.Side.SELL, 1, 100,
                Order.Source.USER, null, Instant.parse("2026-09-05T00:00:00Z")));
        executions.save(new Execution(sell, 100, 1, BigDecimal.ZERO, BigDecimal.ZERO,
                Instant.parse("2026-09-06T00:00:00Z")));
        orders.save(Order.pendingLimit(account, "000002", Order.Side.BUY, 1, 100,
                Order.Source.USER, null, Instant.parse("2026-09-07T00:00:00Z")));
        assertCodes("latest_purchase", "desc", null, "000001", "000002", "000003");
        assertCodes("latest_purchase", "asc", null, "000001", "000002", "000003");
        assertCodes("return_rate", "desc", null, "000001", "000002", "000003");
    }

    @Test void usesLastBuyExecutionAndUnroundedReturnsForSorting() {
        add("000001", 1, "100.00", 100, "2026-09-01T00:00:00Z");
        add("000002", 1, "100.00", 100, "2026-09-02T00:00:00Z");
        var additional = orders.save(Order.pendingLimit(account, "000001", Order.Side.BUY, 1, 100,
                Order.Source.USER, null, Instant.parse("2026-09-03T00:00:00Z")));
        executions.save(new Execution(additional, 100, 1, null, null, Instant.parse("2026-09-04T00:00:00Z")));
        assertCodes("latest_purchase", "desc", "1", "000001");
        holdings.findByAccountIdAndStockCode(account.getId(), "000001").ifPresent(holdings::delete);
        holdings.findByAccountIdAndStockCode(account.getId(), "000002").ifPresent(holdings::delete);
        holdings.flush();
        add("000001", 1, "1000000.02", 1000000, null);
        add("000002", 1, "1000000.01", 1000000, null);
        assertCodes("return_rate", "desc", null, "000002", "000001");
    }

    @Test void detailFailureAlsoReturnsUnavailable() {
        add("000001", 1, "100", 100, null);
        when(market.stockDetails("000001")).thenThrow(new IllegalStateException("업종 조회 실패"));
        assertThatThrownBy(() -> service.list(user.getId(), account.getId(), null, null, null))
                .isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.code()).isEqualTo("HOLDINGS_DATA_UNAVAILABLE"));
    }

    @Test void keepsCostPrecisionAndReservedSharesAndUsesRecordedSectorFallback() {
        add("000001", 3, "100.00", 40, null);
        when(market.stockDetails("000001")).thenReturn(new KiwoomMarketClient.StockDetails("000001", "테스트", ""));
        orders.save(Order.pendingLimit(account, "000001", Order.Side.SELL, 2, 40,
                Order.Source.USER, null, Instant.now()));
        var item = service.list(user.getId(), account.getId(), null, null, null).holdings().getFirst();
        assertThat(item.quantity()).isEqualTo(3);
        assertThat(item.averagePurchasePrice()).isEqualByComparingTo("33.33");
        assertThat(item.unrealizedPnl()).isEqualByComparingTo("20.00");
        assertThat(item.returnPercent()).isEqualByComparingTo("20.0000");
        assertThat(item.sector()).isEqualTo("미분류");
    }

    @Test void rejectsUnauthorizedInactiveAndMissingAccountsBeforeFetchingMarketData() {
        add("000001", 1, "100", 100, null);
        var other = users.save(new User("타인"));
        assertThatThrownBy(() -> service.list(other.getId(), account.getId(), null, null, null))
                .isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.code()).isEqualTo("ACCOUNT_NOT_FOUND"));
        em.createNativeQuery("update accounts set is_active=false where account_id=:id")
                .setParameter("id", account.getId()).executeUpdate();
        em.clear();
        assertThatThrownBy(() -> service.list(user.getId(), account.getId(), null, null, null)).isInstanceOf(AccountException.class);
        assertThatThrownBy(() -> service.list(user.getId(), Long.MAX_VALUE, null, null, null)).isInstanceOf(AccountException.class);
        verify(market, never()).currentPrice(anyString());
    }

    @Test void doesNotHideMarketFailuresInSuccessfulResponses() {
        add("000001", 1, "100", 100, null);
        when(market.currentPrice("000001")).thenThrow(new IllegalStateException("조회 실패"));
        assertThatThrownBy(() -> service.list(user.getId(), account.getId(), null, null, null))
                .isInstanceOfSatisfying(AccountException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(503);
                    assertThat(e.code()).isEqualTo("HOLDINGS_DATA_UNAVAILABLE");
                });
    }

    @Test void httpResponseUsesExactlyTheDocumentedFields() throws Exception {
        add("000001", 3, "100.00", 40, null);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/users/me/accounts/{id}/holdings?sort=market_value&order=desc&limit=3", account.getId())
                        .cookie(new jakarta.servlet.http.Cookie("access_token", tokens.issue(user.getId()).accessToken())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(3))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.holdings[0].length()").value(10))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.holdings[0].stock_code").value("000001"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.holdings[0].stock_name").value("종목000001"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.holdings[0].sector").value("전기·전자"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.holdings[0].quantity").value(3))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.holdings[0].total_cost").value(100))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.holdings[0].average_purchase_price").value(33.33))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.holdings[0].current_price").value(40))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.holdings[0].total_value").value(120))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.holdings[0].unrealized_pnl").value(20))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.holdings[0].return_percent").value(20));
    }

    private void assertCodes(String sort, String order, String limit, String... codes) {
        assertThat(service.list(user.getId(), account.getId(), sort, order, limit).holdings())
                .extracting(item -> item.stockCode()).containsExactly(codes);
    }

    private void add(String code, long quantity, String cost, long price, String time) {
        holdings.save(new Holding(account, code, quantity, new BigDecimal(cost)));
        when(market.currentPrice(code)).thenReturn(price);
        when(market.stockDetails(code)).thenReturn(new KiwoomMarketClient.StockDetails(code, "종목" + code, "전기·전자"));
        if (time != null) {
            var order = orders.save(Order.pendingLimit(account, code, Order.Side.BUY, quantity, price,
                    Order.Source.USER, null, Instant.parse(time).minusSeconds(100)));
            executions.save(new Execution(order, price, quantity, null, null, Instant.parse(time)));
        }
    }
}
