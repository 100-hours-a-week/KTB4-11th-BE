package com.stock_spoon.river_be.user.controller;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.entity.CompetitionParticipant;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionParticipantRepository;
import jakarta.persistence.EntityManager;
import static org.assertj.core.api.Assertions.assertThat;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.config.JwtProperties;
import com.stock_spoon.river_be.market.MarketStream;
import com.stock_spoon.river_be.holding.entity.Holding;
import com.stock_spoon.river_be.holding.repository.HoldingRepository;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
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
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionParticipantRepository participants;
    @Autowired EntityManager entityManager;
    @Autowired AccountRepository accounts;
    @Autowired HoldingRepository holdings;
    @Autowired OrderRepository orders;
    @Autowired JwtEncoder encoder;
    @Autowired JwtProperties jwt;
    @MockitoBean MarketStream stream;
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

        mvc.perform(get("/api/v1/users/ai-server").cookie(aiCookie()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"users":[
                          {"user_id":%d,"accounts":[{
                            "account_id":%d,"participant_id":null,"account_name":"AI 계좌","is_active":true,
                            "cash_balance":1000000,
                            "stocks":[{"stock_code":"005930","total_cost":1000000,"quantity":10}],
                            "pending_orders":[{"order_id":%d,"stock_code":"005930",
                              "order_side":"sell","order_status":"pending","order_type":"limit",
                              "limit_price":250000,"quantity":2}]
                          }]},
                          {"user_id":%d,"accounts":[]}
                        ]}
                        """.formatted(user.getId(), account.getId(), order.getId(), emptyUser.getId()),
                        JsonCompareMode.STRICT));
    }

    @Test
    void returnsMatchingParticipantIdsForBothCompetitionTypesAndNullForGeneralAccount() throws Exception {
        var user = users.save(new User("전략 사용자"));
        var other = users.save(new User("다른 사용자"));
        var general = accounts.save(new Account(user, "일반 AI", 1_000_000));
        var userAi = accounts.save(new Account(user, "사용자 대결 AI", 1_000_000, true));
        var aiAi = accounts.save(new Account(other, "AI 대결 AI", 1_000_000, true));
        var readyAccount = accounts.save(new Account(other, "대기 대결 AI", 1_000_000, true));
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        var first = competitions.save(new Competition(user, "대결 1", Competition.Type.USER_VS_AI, today, today.plusDays(3), 1_000_000, 1));
        var second = competitions.save(new Competition(other, "대결 2", Competition.Type.AI_VS_AI, today, today.plusDays(3), 1_000_000, 2));
        var ready = competitions.save(new Competition(other, "대결 3", Competition.Type.AI_VS_AI, today, today.plusDays(3), 1_000_000, 2));
        var p1 = participants.save(new CompetitionParticipant(first, userAi));
        var p2 = participants.save(new CompetitionParticipant(second, aiAi));
        participants.save(new CompetitionParticipant(ready, readyAccount));
        entityManager.flush();
        entityManager.createNativeQuery("update competitions set state = 'IN_PROGRESS' where competition_id in (:ids)")
                .setParameter("ids", java.util.List.of(first.getId(), second.getId())).executeUpdate();
        entityManager.clear();
        var result = mvc.perform(get("/api/v1/users/ai-server").cookie(aiCookie())).andExpect(status().isOk()).andReturn();
        var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
        var byAccount = new java.util.HashMap<Long, tools.jackson.databind.JsonNode>();
        json.get("users").forEach(u -> u.get("accounts").forEach(a -> byAccount.put(a.get("account_id").asLong(), a)));
        assertThat(byAccount.get(general.getId()).has("participant_id")).isTrue();
        assertThat(byAccount.get(general.getId()).get("participant_id").isNull()).isTrue();
        assertThat(byAccount.get(userAi.getId()).get("participant_id").asLong()).isEqualTo(p1.getId());
        assertThat(byAccount.get(aiAi.getId()).get("participant_id").asLong()).isEqualTo(p2.getId());
        assertThat(byAccount).doesNotContainKey(readyAccount.getId());
    }

    @Test
    void returnsPendingOrdersWithoutCurrentPriceWhenStreamHasNoPrice() throws Exception {
        var user = users.save(new User("사용자"));
        var account = accounts.save(new Account(user, "AI 계좌", 1_000_000));
        orders.save(Order.pendingLimit(account, "005930", Order.Side.BUY, 1, 150_000,
                Order.Source.AI, null, Instant.now()));

        mvc.perform(get("/api/v1/users/ai-server").cookie(aiCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].accounts[0].pending_orders[0].stock_code").value("005930"))
                .andExpect(jsonPath("$.users[0].accounts[0].pending_orders[0].current_stock_price").doesNotExist());
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
                          "account_id":%d,"participant_id":null,"account_name":"빈 AI 계좌","is_active":true,
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
