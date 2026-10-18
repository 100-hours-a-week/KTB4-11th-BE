package com.stock_spoon.river_be.market.kiwoom;

import com.stock_spoon.river_be.market.MarketStream;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"kiwoom.collector.enabled=true", "kiwoom.collector.secret=test-only-collector-shared-secret"})
class CollectorSecurityTests {
    @Autowired WebApplicationContext context;
    @MockitoBean KiwoomRestTransport transport;
    @MockitoBean RedisCollectorHeartbeat heartbeat;
    @MockitoBean MarketStream stream;
    @MockitoBean StockMetadataCache metadata;
    @LocalServerPort int port;
    private MockMvc mvc;
    private static final String BODY = "{\"apiId\":\"ka10100\",\"stockCode\":\"005930\",\"collectorId\":\"owner-1\"}";

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(heartbeat.ownsLease()).thenReturn(true);
        when(heartbeat.ownerId()).thenReturn("owner-1");
        when(transport.query(anyString(), anyString(), anyMap())).thenReturn(Map.of("return_code", 0));
    }

    @Test
    void missingWrongAndDuplicateSecretsCannotReachExternalQuery() throws Exception {
        mvc.perform(post("/internal/kiwoom/query").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/kiwoom/query").header("X-Kiwoom-Collector-Key", "wrong")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/kiwoom/query").header("X-Kiwoom-Collector-Key", "test-only-collector-shared-secret", "wrong")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(transport);
    }

    @Test
    void retiredCurrentPriceTrIsRejectedEvenWithValidSecret() throws Exception {
        mvc.perform(post("/internal/kiwoom/query").header("X-Kiwoom-Collector-Key", "test-only-collector-shared-secret")
                .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("ka10100", "ka10001")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(transport);
    }

    @Test
    void dedicatedSecretWorksWithoutUserLoginOrCsrf() throws Exception {
        mvc.perform(post("/internal/kiwoom/query").header("X-Kiwoom-Collector-Key", "test-only-collector-shared-secret")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.return_code").value(0));
        verify(transport).query("ka10100", "/api/dostk/stkinfo", Map.of("stk_cd", "005930"));
    }

    @Test
    void unavailableCollectorAndWriteTrAreRejected() throws Exception {
        when(heartbeat.ownsLease()).thenReturn(false);
        mvc.perform(post("/internal/kiwoom/query").header("X-Kiwoom-Collector-Key", "test-only-collector-shared-secret")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isServiceUnavailable());
        when(heartbeat.ownsLease()).thenReturn(true);
        mvc.perform(post("/internal/kiwoom/query").header("X-Kiwoom-Collector-Key", "test-only-collector-shared-secret")
                .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("ka10100", "kt10000")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(transport);
    }

    @Test
    void realHttpClientReachesAuthenticatedCollectorWithoutKiwoomCredentials() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("coord:kiwoom:collector")).thenReturn("owner-1");
        var tokens = mock(KiwoomTokenProvider.class);
        var config = new KiwoomConfig();
        var remote = config.kiwoomRestTransport(tokens, null, redis, false,
                "http://localhost:" + port, "test-only-collector-shared-secret");
        org.assertj.core.api.Assertions.assertThat(remote.query("ka10100", "/api/dostk/stkinfo", Map.of("stk_cd", "005930")))
                .containsEntry("return_code", 0);
        verify(transport).query("ka10100", "/api/dostk/stkinfo", Map.of("stk_cd", "005930"));
        verifyNoInteractions(tokens);
    }

    @Test
    void wrongCollectorIdentityAndLeaseLossDuringQueryAreRejected() throws Exception {
        mvc.perform(post("/internal/kiwoom/query").header("X-Kiwoom-Collector-Key", "test-only-collector-shared-secret")
                .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("owner-1", "owner-old")))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(transport);
        when(heartbeat.ownsLease()).thenReturn(true, false);
        mvc.perform(post("/internal/kiwoom/query").header("X-Kiwoom-Collector-Key", "test-only-collector-shared-secret")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isServiceUnavailable());
    }

    @Test
    void metadataHttpPathReturnsOnlyStableFields() {
        var fields = Map.<String, Object>of("return_code", 0, "code", "005930", "name", "삼성전자",
                "marketCode", "0", "upName", "전기전자");
        when(metadata.load(eq("005930"), any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(1)).get());
        when(transport.metadata("005930")).thenReturn(fields);
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("coord:kiwoom:collector")).thenReturn("owner-1");
        var remote = new KiwoomConfig().kiwoomRestTransport(mock(KiwoomTokenProvider.class), null, redis,
                false, "http://localhost:" + port, "test-only-collector-shared-secret");
        var normal = new KiwoomMarketClient(remote, new StockMetadataCache(redis));
        org.assertj.core.api.Assertions.assertThat(normal.stockDetails("005930").stockName()).isEqualTo("삼성전자");
        verify(transport).metadata("005930");
        verify(transport, never()).query(anyString(), anyString(), anyMap());
    }
}
