package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.config.JwtProperties;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class OrderControllerTests {
    // 주문 접수 테스트는 장중으로 고정한다. 실제 시각이 15:30 이후면 구독 갱신이 주문을 취소한다.
    @org.springframework.boot.test.context.TestConfiguration
    static class MarketHours {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        OrderService marketHoursOrderService(AccountRepository accounts, OrderRepository orders, HoldingRepository holdings) {
            return new OrderService(accounts, orders, holdings,
                    java.time.Clock.fixed(Instant.parse("2026-10-01T01:00:00Z"), java.time.ZoneOffset.UTC));
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired OrderRepository orders;
    @Autowired JwtTokenProvider tokens;
    @Autowired JwtEncoder encoder;
    @Autowired JwtProperties jwtProperties;
    @MockitoBean OrderMarketValidator market;
    @MockitoBean KiwoomStockStream stream;
    @MockitoBean com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient marketClient;
    private MockMvc mvc;
    private User user;
    private Account account;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        user = users.save(new User("AI 주문 사용자"));
        account = accounts.save(new Account(user, "자동매매 계좌", 1_000_000));
        when(stream.whenSubscribed(anyString())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(marketClient.currentPrice(anyString())).thenThrow(new IllegalStateException("test price unavailable"));
    }

    @Test
    void acceptsLimitOrderWithReasonAndEachRequestCreatesAnOrder() throws Exception {
        String body = """
                {"stock_code":"005930","order_side":"buy","order_type":"limit",
                 "limit_price":70000,"quantity":2,
                 "reason":"매수 판단"}
                """;
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/accounts/{accountId}/orders", account.getId())
                            .cookie(aiCookie(user)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.order_status").value("pending"))
                    .andExpect(jsonPath("$.reserved_cash").value(140000));
        }
        assertThat(orders.count()).isEqualTo(2);
        assertThat(orders.findAll()).allSatisfy(order -> {
            assertThat(order.getSource()).isEqualTo(Order.Source.AI);
            assertThat(order.getReport().getReason()).isEqualTo("매수 판단");
        });
    }

    @Test
    void rejectsAnotherUsersAccountAndInvalidOrderWithoutSaving() throws Exception {
        User other = users.save(new User("다른 사용자"));
        String body = """
                {"stock_code":"005930","order_side":"buy","order_type":"limit",
                 "limit_price":70000,"quantity":1,
                 "reason":"매수 판단"}
                """;
        mvc.perform(post("/api/v1/accounts/{accountId}/orders", account.getId())
                        .cookie(aiCookie(other)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/accounts/{accountId}/orders", account.getId())
                        .cookie(aiCookie(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"limit\"", "\"market\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/accounts/{accountId}/orders", account.getId())
                        .cookie(aiCookie(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"reason\":\"매수 판단\"",
                                "\"reason\":null")))
                .andExpect(status().isBadRequest());
        assertThat(orders.count()).isZero();
    }

    @Test
    void regularUserTokenCannotCreateOrder() throws Exception {
        mvc.perform(post("/api/v1/accounts/{accountId}/orders", account.getId())
                        .cookie(new Cookie("access_token", tokens.issue(user.getId()).accessToken()))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stock_code":"005930","order_side":"buy","order_type":"limit",
                                 "limit_price":70000,"quantity":1,
                                 "reason":"매수 판단"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AI_ORDER_ONLY"));
        assertThat(orders.count()).isZero();
    }

    @Test
    void subscriptionFailureReturns503WithoutAnOrderOrReservation() throws Exception {
        when(stream.whenSubscribed("005930")).thenReturn(java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("registration rejected")));
        mvc.perform(post("/api/v1/accounts/{accountId}/orders", account.getId())
                        .cookie(aiCookie(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stock_code":"005930","order_side":"buy","order_type":"limit",
                                 "limit_price":70000,"quantity":1,
                                 "reason":"매수 판단"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MARKET_STREAM_UNAVAILABLE"));
        assertThat(orders.count()).isZero();
        assertThat(orders.reservedCash(account.getId(), Order.Status.PENDING)).isZero();
        assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(1_000_000);
    }

    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired ExecutionRepository executions;

    @Test
    void acceptsLongTextAndRejectsInvalidReasonWithoutSaving() throws Exception {
        String body = "{\"stock_code\":\"005930\",\"order_side\":\"buy\",\"order_type\":\"limit\","
                + "\"limit_price\":70000,\"quantity\":1,\"reason\":%s}";
        mvc.perform(post("/api/v1/accounts/{accountId}/orders", account.getId())
                        .cookie(aiCookie(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("\"" + "근거".repeat(1000) + "\"")))
                .andExpect(status().isCreated());
        entityManager.flush();
        entityManager.clear();
        assertThat(orders.findAll().getFirst().getReport().getReason()).isEqualTo("근거".repeat(1000));
        for (String invalid : java.util.List.of("null", "\" \"", "{}", "\"" + "a".repeat(100001) + "\"")) {
            mvc.perform(post("/api/v1/accounts/{accountId}/orders", account.getId())
                            .cookie(aiCookie(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(body.formatted(invalid)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(orders.count()).isEqualTo(1);
        assertThat(((Number) entityManager.createNativeQuery("select count(*) from ai_order_reports")
                .getSingleResult()).longValue()).isEqualTo(1);
    }

    @Test
    void databaseRejectsSecondReportForSameOrder() {
        var order = orders.save(Order.pendingLimit(account, "005930", Order.Side.BUY,
                1, 70000, Order.Source.AI, "근거", Instant.now()));
        entityManager.flush();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> {
            entityManager.persist(new AiOrderReport(order, "중복"));
            entityManager.flush();
        })).isInstanceOf(jakarta.persistence.PersistenceException.class);
    }


    @Test
    void readsOwnedReportAndAggregatesSellExecutions() throws Exception {
        var order = orders.save(Order.pendingLimit(account, "000660", Order.Side.SELL,
                2, 196000, Order.Source.AI, "매도 판단", Instant.parse("2026-09-03T05:20:00Z")));
        executions.save(new Execution(order, 196000, 1, new java.math.BigDecimal("9400"),
                new java.math.BigDecimal("5.0375"), Instant.parse("2026-09-03T05:21:00Z")));
        executions.save(new Execution(order, 197000, 1, new java.math.BigDecimal("10400"),
                new java.math.BigDecimal("5.5734"), Instant.parse("2026-09-03T05:22:00Z")));
        order.execute();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/accounts/{account}/orders/{order}/ai-report", account.getId(), order.getId())
                        .cookie(new Cookie("access_token", tokens.issue(user.getId()).accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("매도 판단"))
                .andExpect(jsonPath("$.report_id").doesNotExist())
                .andExpect(jsonPath("$.stock_name").value("[더미] 종목명"))
                .andExpect(jsonPath("$.decided_at").value("2026-09-03T14:20:00+09:00"))
                .andExpect(jsonPath("$.buy_analysis").isEmpty())
                .andExpect(jsonPath("$.sell_analysis.trade_result.holding_days").value(0))
                .andExpect(jsonPath("$.sell_analysis.trade_result.target_return_percent").value(0))
                .andExpect(jsonPath("$.sell_analysis.trade_result.target_reached").value(false))
                .andExpect(jsonPath("$.sell_analysis.trade_result.stop_loss_triggered").value(false))
                .andExpect(jsonPath("$.sell_analysis.buy_decision.buy_report_id").value(0))
                .andExpect(jsonPath("$.sell_analysis.buy_decision.summary").value("[더미] 매수 당시 판단"))
                .andExpect(jsonPath("$.sell_analysis.holding_changes[0].summary").value("[더미] 보유 중 변화"))
                .andExpect(jsonPath("$.sell_analysis.sell_decision").value("[더미] 매도 판단"))
                .andExpect(jsonPath("$.sell_analysis.expectation_vs_outcome.expected_return_min_percent").value(0))
                .andExpect(jsonPath("$.sell_analysis.expectation_vs_outcome.expected_return_max_percent").value(0))
                .andExpect(jsonPath("$.sell_analysis.expectation_vs_outcome.summary").value("[더미] 예상과 결과"))
                .andExpect(jsonPath("$.order_status").doesNotExist())
                .andExpect(jsonPath("$.execution.execution_count").doesNotExist())
                .andExpect(jsonPath("$.execution.execution_price").value(196500))
                .andExpect(jsonPath("$.execution.execution_quantity").value(2))
                .andExpect(jsonPath("$.execution.trade_amount").value(393000))
                .andExpect(jsonPath("$.sell_analysis.trade_result.average_buy_price").value(186600))
                .andExpect(jsonPath("$.sell_analysis.trade_result.realized_pnl").value(19800))
                .andExpect(jsonPath("$.sell_analysis.trade_result.realized_return_percent").value(5.3055));
        var other = users.save(new User("다른 조회 사용자"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/accounts/{account}/orders/{order}/ai-report", account.getId(), order.getId())
                        .cookie(new Cookie("access_token", tokens.issue(other.getId()).accessToken())))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/accounts/{account}/orders/{order}/ai-report", account.getId(), order.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pendingReportHasNoExecutionAndMissingReportReturns404() throws Exception {
        var order = orders.save(Order.pendingLimit(account, "005930", Order.Side.BUY,
                1, 70000, Order.Source.AI, "판단".repeat(600), Instant.now()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/accounts/{account}/orders/{order}/ai-report", account.getId(), order.getId())
                        .cookie(new Cookie("access_token", tokens.issue(user.getId()).accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("판단".repeat(600)))
                .andExpect(jsonPath("$.order_status").doesNotExist())
                .andExpect(jsonPath("$.execution").isEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/accounts/{account}/orders/{order}/ai-report", account.getId(), Long.MAX_VALUE)
                        .cookie(new Cookie("access_token", tokens.issue(user.getId()).accessToken())))
                .andExpect(status().isNotFound());
    }

    private Cookie aiCookie(User owner) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer()).subject(owner.getId().toString())
                .issuedAt(now).expiresAt(now.plusSeconds(900))
                .claim("type", "access").claim("actor", "AI").build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        return new Cookie("access_token", token);
    }
}
