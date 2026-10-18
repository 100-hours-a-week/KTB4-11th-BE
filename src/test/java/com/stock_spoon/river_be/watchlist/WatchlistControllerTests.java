package com.stock_spoon.river_be.watchlist;

import com.stock_spoon.river_be.watchlist.entity.Stock;
import com.stock_spoon.river_be.watchlist.repository.StockRepository;
import com.stock_spoon.river_be.watchlist.repository.WatchlistRepository;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"AUTH_CSRF_DISABLED=false", "kakao.frontend-origin=http://localhost:3000", "kiwoom.stream.enabled=false"})
@Transactional
class WatchlistControllerTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired StockRepository stocks;
    @Autowired WatchlistRepository watchlists;
    @Autowired JwtTokenProvider tokens;
    MockMvc mvc;
    Cookie owner;
    Cookie other;
    static final String URL = "/api/v2/users/me/watchlist";

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = cookie(users.save(new User("관심 사용자")));
        other = cookie(users.save(new User("다른 사용자")));
        stocks.save(new Stock("005930", "삼성전자", "KOSPI", "반도체"));
    }
    Cookie cookie(User user) { return new Cookie("access_token", tokens.issue(user.getId()).accessToken()); }
    void add() throws Exception {
        mvc.perform(post(URL).cookie(owner).with(csrf()).contentType("application/json")
                .content("{\"stock_code\":\"005930\"}")).andExpect(status().isCreated());
    }
    @Test void savesOnceAndReturnsMetadataWithoutPricesOrAccounts() throws Exception {
        add();
        mvc.perform(post(URL).cookie(owner).with(csrf()).contentType("application/json")
                .content("{\"stock_code\":\"005930\"}")).andExpect(status().isOk());
        assertThat(watchlists.count()).isEqualTo(1);
        mvc.perform(get(URL).cookie(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.watchlists[0].stock_code").value("005930"))
                .andExpect(jsonPath("$.watchlists[0].stock_name").value("삼성전자"))
                .andExpect(jsonPath("$.watchlists[0].sector").value("반도체"))
                .andExpect(jsonPath("$.account_id").doesNotExist())
                .andExpect(jsonPath("$.watchlists[0].current_price").doesNotExist());
    }
    @Test void isolatesUsersAndRepeatedDeletionSucceeds() throws Exception {
        add();
        mvc.perform(get(URL).cookie(other)).andExpect(jsonPath("$.watchlists").isEmpty());
        mvc.perform(delete(URL+"/005930").cookie(other).with(csrf())).andExpect(status().isOk());
        assertThat(watchlists.count()).isEqualTo(1);
        for (int i=0;i<2;i++) mvc.perform(delete(URL+"/005930").cookie(owner).with(csrf())).andExpect(status().isOk());
        mvc.perform(get(URL).cookie(owner)).andExpect(jsonPath("$.watchlists").isEmpty());
    }
    @Test void rejectsInvalidAndMissingStocks() throws Exception {
        for (String body : java.util.List.of("{}", "{\"stock_code\":\"5930\"}", "{\"stock_code\":null}", "{\"stock_code\":123456}")) {
            mvc.perform(post(URL).cookie(owner).with(csrf()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(post(URL).cookie(owner).with(csrf()).contentType("application/json")
                .content("{\"stock_code\":\"999999\"}")).andExpect(status().isNotFound());
        mvc.perform(delete(URL+"/bad").cookie(owner).with(csrf())).andExpect(status().isBadRequest());
        assertThat(watchlists.count()).isZero();
    }
    @Test void requiresAuthenticationAndCsrfAndAllowsDeletePreflight() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(post(URL).cookie(owner).contentType("application/json").content("{\"stock_code\":\"005930\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(URL+"/005930").cookie(owner)).andExpect(status().isForbidden());
        mvc.perform(options(URL+"/005930").header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "DELETE")).andExpect(status().isOk());
    }
    @Test void rejectsAmbiguousCodes() throws Exception {
        stocks.save(new Stock("005930", "중복 코드", "KOSDAQ", null));
        mvc.perform(post(URL).cookie(owner).with(csrf()).contentType("application/json")
                .content("{\"stock_code\":\"005930\"}")).andExpect(status().isConflict());
        assertThat(watchlists.count()).isZero();
    }
}