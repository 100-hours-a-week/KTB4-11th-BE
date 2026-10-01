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
                 "reason":{"decision_id":"d-1","summary":"매수 판단"}}
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
            assertThat(order.getDecisionId()).isEqualTo("d-1");
            assertThat(order.getDecisionSummary()).isEqualTo("매수 판단");
        });
    }

    @Test
    void rejectsAnotherUsersAccountAndInvalidOrderWithoutSaving() throws Exception {
        User other = users.save(new User("다른 사용자"));
        String body = """
                {"stock_code":"005930","order_side":"buy","order_type":"limit",
                 "limit_price":70000,"quantity":1,
                 "reason":{"decision_id":"d-2","summary":"매수 판단"}}
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
                        .content(body.replace("\"reason\":{\"decision_id\":\"d-2\",\"summary\":\"매수 판단\"}",
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
                                 "reason":{"decision_id":"d-3","summary":"매수 판단"}}
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
                                 "reason":{"decision_id":"d-4","summary":"매수 판단"}}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MARKET_STREAM_UNAVAILABLE"));
        assertThat(orders.count()).isZero();
        assertThat(orders.reservedCash(account.getId(), Order.Status.PENDING)).isZero();
        assertThat(accounts.findById(account.getId()).orElseThrow().getCashBalance()).isEqualTo(1_000_000);
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
