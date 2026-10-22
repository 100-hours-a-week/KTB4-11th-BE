package com.stock_spoon.river_be.stock.controller;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"kiwoom.stream.enabled=false"})
class StockControllerTests {
    @Autowired WebApplicationContext context;
    @MockitoBean KiwoomMarketClient market;
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void returnsOnlyCodeAndNameWithoutAccountOrHolding() throws Exception {
        when(market.stockDetails("005930")).thenReturn(new KiwoomMarketClient.StockDetails("005930", "삼성전자", "전기/전자"));
        mvc.perform(get("/api/v2/stocks/005930").with(jwt().jwt(j -> j.subject("1"))))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"stock_code\":\"005930\",\"stock_name\":\"삼성전자\"}"))
                .andExpect(jsonPath("$.length()").value(2));
        verify(market).stockDetails("005930");
    }

    @Test void preservesAlphanumericCodeAndProviderName() throws Exception {
        when(market.stockDetails("12345A")).thenReturn(new KiwoomMarketClient.StockDetails("12345A", "NAVER", ""));
        mvc.perform(get("/api/v2/stocks/12345A").with(jwt().jwt(j -> j.subject("1"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stock_name").value("NAVER"));
    }

    @Test void rejectsInvalidCodeBeforeLookup() throws Exception {
        for (String code : new String[]{"12345", "1234567", "00593a", "00593!", "0059 0"}) {
            mvc.perform(get("/api/v2/stocks/{code}", code).with(jwt().jwt(j -> j.subject("1"))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STOCK_CODE"));
        }
        verifyNoInteractions(market);
    }

    @Test void hidesProviderFailureAndDoesNotMisreportNotFound() throws Exception {
        when(market.stockDetails("005930")).thenThrow(new IllegalStateException("provider secret"));
        mvc.perform(get("/api/v2/stocks/005930").with(jwt().jwt(j -> j.subject("1"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STOCK_INFO_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("종목 정보를 조회할 수 없습니다."));
    }

    @Test void mapsHttpTransportFailureToUnavailable() throws Exception {
        when(market.stockDetails("005930")).thenThrow(new org.springframework.web.client.ResourceAccessException("provider timeout"));
        mvc.perform(get("/api/v2/stocks/005930").with(jwt().jwt(j -> j.subject("1"))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STOCK_INFO_UNAVAILABLE"));
    }

    @Test void requiresUserAuthentication() throws Exception {
        mvc.perform(get("/api/v2/stocks/005930")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v2/stocks/005930").with(jwt().jwt(j -> j.subject("ai-server").claim("actor", "AI"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(market);
    }
}

