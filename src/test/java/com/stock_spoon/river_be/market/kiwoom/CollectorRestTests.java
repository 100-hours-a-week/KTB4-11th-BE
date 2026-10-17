package com.stock_spoon.river_be.market.kiwoom;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CollectorRestTests {
    private static final String SECRET = "test-only-collector-shared-secret";

    @Test
    void normalBeForwardsDailyClosingPriceWithFixedReadOnlyBody() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("coord:kiwoom:collector")).thenReturn("owner-1");
        var builder = RestClient.builder().baseUrl("http://collector:8080");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://collector:8080/internal/kiwoom/query"))
                .andExpect(content().json("{\"apiId\":\"ka10081\",\"stockCode\":\"005930\",\"collectorId\":\"owner-1\",\"baseDate\":\"20261016\"}"))
                .andRespond(withSuccess("""
                        {"return_code":0,"stk_cd":"005930","stk_dt_pole_chart_qry":[{"dt":"20261016","cur_prc":"70000"}]}
                        """, MediaType.APPLICATION_JSON));
        var client = new KiwoomMarketClient(new RemoteKiwoomRestTransport(builder.build(), redis, SECRET));
        assertThat(client.closingPrice("005930", java.time.LocalDate.of(2026, 10, 16))).isEqualTo(70000);
        assertThat(CollectorQuery.from("ka10081", "/api/dostk/chart",
                Map.of("stk_cd", "005930", "base_dt", "20261016", "upd_stkpc_tp", "0"), "owner-1").body())
                .containsEntry("upd_stkpc_tp", "0");
        assertThatThrownBy(() -> CollectorQuery.from("ka10081", "/api/dostk/chart",
                Map.of("stk_cd", "005930", "base_dt", "20260230", "upd_stkpc_tp", "0"), "owner-1"))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void normalBeForwardsOnlyQueryAndChecksCollectorIdentity() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("coord:kiwoom:collector")).thenReturn("owner-1");
        var builder = RestClient.builder().baseUrl("http://collector:8080");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://collector:8080/internal/kiwoom/query"))
                .andExpect(header("X-Kiwoom-Collector-Key", SECRET))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().json("{\"apiId\":\"ka10001\",\"stockCode\":\"005930\",\"collectorId\":\"owner-1\"}"))
                .andRespond(withSuccess("{\"return_code\":0,\"stk_cd\":\"005930\",\"cur_prc\":\"70000\"}", MediaType.APPLICATION_JSON));
        var client = new KiwoomMarketClient(new RemoteKiwoomRestTransport(builder.build(), redis, SECRET));
        assertThat(client.currentPrice("005930")).isEqualTo(70000);
        server.verify();
    }

    @Test
    void absentOrChangedCollectorRejectsQuery() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var builder = RestClient.builder().baseUrl("http://collector:8080");
        var server = MockRestServiceServer.bindTo(builder).build();
        var remote = new RemoteKiwoomRestTransport(builder.build(), redis, SECRET);
        assertThatThrownBy(() -> remote.query("ka10001", "/api/dostk/stkinfo", Map.of("stk_cd", "005930")))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
        when(values.get("coord:kiwoom:collector")).thenReturn("owner-1", "owner-2");
        server.expect(requestTo("http://collector:8080/internal/kiwoom/query"))
                .andRespond(withSuccess("{\"return_code\":0}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> remote.query("ka10001", "/api/dostk/stkinfo", Map.of("stk_cd", "005930")))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void onlyKnownReadOnlyTrAndFixedBodiesAreAccepted() {
        assertThatThrownBy(() -> CollectorQuery.from("kt10000", "/api/dostk/ordr", Map.of("stk_cd", "005930"), "owner"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CollectorQuery.from("ka10001", "/arbitrary", Map.of("stk_cd", "005930"), "owner"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CollectorQuery("ka10100", "bad", "owner").body())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CollectorQuery("ka20001", null, "owner").body())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unavailableConfigurationAndHttpFailureDoNotIssueKiwoomToken() {
        var config = new KiwoomConfig();
        var tokens = mock(KiwoomTokenProvider.class);
        var redis = mock(StringRedisTemplate.class);
        var unconfigured = config.kiwoomRestTransport(tokens, null, redis, false, "", "");
        assertThatThrownBy(() -> unconfigured.query("ka10001", "/api/dostk/stkinfo", Map.of("stk_cd", "005930")))
                .isInstanceOf(IllegalStateException.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("coord:kiwoom:collector")).thenReturn("owner-1");
        var builder = RestClient.builder().baseUrl("http://collector:8080");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://collector:8080/internal/kiwoom/query"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                        .body("sensitive upstream response"));
        var remote = new RemoteKiwoomRestTransport(builder.build(), redis, SECRET);
        assertThatThrownBy(() -> remote.query("ka10001", "/api/dostk/stkinfo", Map.of("stk_cd", "005930")))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("sensitive");
        server.verify();
        verifyNoInteractions(tokens);
    }
}
