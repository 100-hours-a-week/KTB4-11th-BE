package com.stock_spoon.river_be.account.service;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.holding.entity.Holding;
import com.stock_spoon.river_be.holding.repository.HoldingRepository;
import com.stock_spoon.river_be.market.RedisMarketPrice;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class AccountHoldingDetailTests {
    @Autowired WebApplicationContext context;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired HoldingRepository holdings;
    @MockitoBean RedisMarketPrice prices;
    @MockitoBean KiwoomMarketClient market;
    MockMvc mvc;
    User owner;
    Account account;
    String url(String code) { return "/api/v1/accounts/" + account.getId() + "/holdings/" + code; }
    @BeforeEach void setup() {
        owner = users.save(new User("단일 보유 사용자"));
        account = accounts.save(new Account(owner, "조회 계좌", 1000000));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void returnsCostAndRoundedAverageWithoutMarketData() throws Exception {
        holdings.save(new Holding(account, "005930", 3, new BigDecimal("100.00")));
        mvc.perform(get(url("005930")).with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.account_id").value(account.getId()))
                .andExpect(jsonPath("$.stock_code").value("005930"))
                .andExpect(jsonPath("$.is_held").value(true)).andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.total_cost").value(100.00))
                .andExpect(jsonPath("$.average_purchase_price").value(33.33))
                .andExpect(jsonPath("$.length()").value(6));
        verifyNoInteractions(prices, market);
    }
    @Test void returnsUnheldForMissingOrFullySoldStock() throws Exception {
        mvc.perform(get(url("005930")).with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.is_held").value(false))
                .andExpect(jsonPath("$.quantity").value(0)).andExpect(jsonPath("$.total_cost").value(0))
                .andExpect(jsonPath("$.average_purchase_price").value(0));
        verifyNoInteractions(prices, market);
    }
    @Test void rejectsOtherAndMissingAccounts() throws Exception {
        var other = users.save(new User("타인"));
        mvc.perform(get(url("005930")).with(jwt().jwt(j -> j.subject(other.getId().toString()))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mvc.perform(get("/api/v1/accounts/9223372036854775807/holdings/005930")
                .with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        verifyNoInteractions(prices, market);
    }
    @Test void rejectsInvalidStockCodes() throws Exception {
        for (String code : new String[]{"5930", "00593a", "1234567"}) {
            mvc.perform(get(url(code)).with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STOCK_CODE"));
        }
        verifyNoInteractions(prices, market);
    }
    @Test void roundsHalfUpAndReflectsFullSale() throws Exception {
        var holding = holdings.save(new Holding(account, "005930", 6, new BigDecimal("100.00")));
        mvc.perform(get(url("005930")).with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.average_purchase_price").value(16.67));
        holdings.delete(holding);
        holdings.flush();
        mvc.perform(get(url("005930")).with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.is_held").value(false));
        verifyNoInteractions(prices, market);
    }
    @Test void rejectsInactiveAccount() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(account, "active", false);
        accounts.flush();
        mvc.perform(get(url("005930")).with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        verifyNoInteractions(prices, market);
    }
    @Test void requiresUserAuthentication() throws Exception {
        mvc.perform(get(url("005930"))).andExpect(status().isUnauthorized());
        mvc.perform(get(url("005930")).with(jwt().jwt(j -> j.subject("ai-server").claim("actor", "AI"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(prices, market);
    }
}
