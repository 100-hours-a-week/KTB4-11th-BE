package com.stock_spoon.river_be.user.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.config.JwtProperties;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream;
import com.stock_spoon.river_be.order.Holding;
import com.stock_spoon.river_be.order.HoldingRepository;
import com.stock_spoon.river_be.order.Order;
import com.stock_spoon.river_be.order.OrderRepository;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
class AiUserSnapshotControllerTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired HoldingRepository holdings;
    @Autowired OrderRepository orders;
    @Autowired JwtEncoder encoder;
    @Autowired JwtProperties jwt;
    @MockitoBean KiwoomStockStream stream;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void returnsTheAgreedJsonWithLastPriceAndUsersWithoutManagedAccounts() throws Exception {
        var user = users.save(new User("사용자"));
        var emptyUser = users.save(new User("계좌 없는 사용자"));
        var account = accounts.save(new Account(user, "AI 계좌", 1_000_000));
        holdings.save(new Holding(account, "005930", 10, new BigDecimal("1000000.00")));
        var order = orders.save(Order.pendingLimit(account, "005930", Order.Side.SELL, 2,
                250_000, Order.Source.AI, null, Instant.now()));
        when(stream.latest("005930")).thenReturn(Optional.of(new KiwoomStockStream.StockPrice(
                "005930", new BigDecimal("200000"), BigDecimal.ZERO, BigDecimal.ZERO,
                LocalTime.NOON, Instant.parse("2026-09-28T03:00:00Z"))));

        mvc.perform(get("/api/v1/users/ai-server").cookie(aiCookie()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"users":[
                          {"user_id":%d,"accounts":[{
                            "account_id":%d,"account_name":"AI 계좌","is_active":true,
                            "cash_balance":1000000,
                            "stocks":[{"stock_code":"005930","total_cost":1000000,"quantity":10}],
                            "pending_orders":[{"order_id":%d,"stock_code":"005930",
                              "order_side":"sell","order_status":"pending","order_type":"limit",
                              "limit_price":250000,"quantity":2,"current_stock_price":200000}]
                          }]},
                          {"user_id":%d,"accounts":[]}
                        ]}
                        """.formatted(user.getId(), account.getId(), order.getId(), emptyUser.getId()),
                        JsonCompareMode.STRICT));
    }

    @Test
    void returns503ForTheEntireRequestWhenAPendingStockPriceIsMissing() throws Exception {
        var user = users.save(new User("사용자"));
        var account = accounts.save(new Account(user, "AI 계좌", 1_000_000));
        orders.save(Order.pendingLimit(account, "005930", Order.Side.BUY, 1, 150_000,
                Order.Source.AI, null, Instant.now()));
        when(stream.latest("005930")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/users/ai-server").cookie(aiCookie()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("""
                        {"code":"MARKET_DATA_UNAVAILABLE",
                         "message":"대기 주문 종목의 현재가를 확인할 수 없습니다."}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void returnsEmptyUsersAndAccountsWithoutNeedingMarketPrices() throws Exception {
        mvc.perform(get("/api/v1/users/ai-server").cookie(aiCookie()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"users\":[]}", JsonCompareMode.STRICT));
        var user = users.save(new User("사용자"));
        var account = accounts.save(new Account(user, "빈 AI 계좌", 1_000_000));
        mvc.perform(get("/api/v1/users/ai-server").cookie(aiCookie()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"users":[{"user_id":%d,"accounts":[{
                          "account_id":%d,"account_name":"빈 AI 계좌","is_active":true,
                          "cash_balance":1000000,"stocks":[],"pending_orders":[]
                        }]}]}
                        """.formatted(user.getId(), account.getId()), JsonCompareMode.STRICT));
    }

    private Cookie aiCookie() {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(jwt.issuer()).subject("ai-server")
                .issuedAt(now).expiresAt(now.plusSeconds(900))
                .claim("type", "access").claim("actor", "AI").build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        return new Cookie("access_token", token);
    }
}
