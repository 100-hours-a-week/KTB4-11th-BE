package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient.KospiIndex;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class KospiIndexControllerTests {
    @Autowired WebApplicationContext context;
    @Autowired KospiIndexService service;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean KiwoomMarketClient client;

    @Test
    void returnsCachedIndexWithoutCallingKiwoomForEachFrontendRequest() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var request = get("/api/v1/market/indices/kospi");
        var auth = new Cookie("access_token", tokens.issue(1L).accessToken());

        mvc.perform(request).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/market/indices/kospi").cookie(auth))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MARKET_DATA_UNAVAILABLE"));
        verifyNoInteractions(client);

        when(client.kospi()).thenReturn(new KospiIndex(new BigDecimal("2817.42"),
                new BigDecimal("-12.50"), new BigDecimal("-0.44"),
                Instant.parse("2026-09-26T06:30:00Z")));
        service.refresh();

        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/v1/market/indices/kospi").cookie(auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.value").value(2817.42))
                    .andExpect(jsonPath("$.change").value(-12.50))
                    .andExpect(jsonPath("$.changeRate").value(-0.44))
                    .andExpect(jsonPath("$.fetchedAt").value("2026-09-26T06:30:00Z"));
        }
        verify(client, times(1)).kospi();
    }
}