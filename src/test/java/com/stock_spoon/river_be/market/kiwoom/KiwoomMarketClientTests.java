package com.stock_spoon.river_be.market.kiwoom;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KiwoomMarketClientTests {
    private MockRestServiceServer server;
    private KiwoomMarketClient client;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl("https://api.kiwoom.com");
        server = MockRestServiceServer.bindTo(builder).build();
        var tokens = mock(KiwoomTokenProvider.class);
        when(tokens.accessToken()).thenReturn("test-token");
        client = new KiwoomMarketClient(builder.build(), tokens);
    }

    @Test
    void readsNameAndIndustryWithoutUsingPreviousCloseAsCurrentPrice() {
        server.expect(requestTo("https://api.kiwoom.com/api/dostk/stkinfo"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-id", "ka10100"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(content().json("{\"stk_cd\":\"005930\"}"))
                .andRespond(withSuccess("""
                        {"return_code":0,"code":"005930","name":"삼성전자","upName":"전기·전자","lastPrice":"99999"}
                        """, MediaType.APPLICATION_JSON));
        for (String body : new String[]{
                "{\"return_code\":0,\"code\":\"000660\",\"name\":\"타종목\"}",
                "{\"return_code\":0,\"code\":\"005930\",\"name\":\"\"}",
                "{\"return_code\":1,\"code\":\"005930\",\"name\":\"삼성전자\"}"}) {
            server.expect(requestTo("https://api.kiwoom.com/api/dostk/stkinfo"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        var detail = client.stockDetails("005930");
        assertThat(detail.stockCode()).isEqualTo("005930");
        assertThat(detail.stockName()).isEqualTo("삼성전자");
        assertThat(detail.sector()).isEqualTo("전기·전자");
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.stockDetails("005930")).isInstanceOf(IllegalStateException.class);
        }
        server.verify();
    }

    @Test
    void spacesQueriesEvenWhenConcurrentCallsWaitForTokenRefresh() throws Exception {
        var builder = RestClient.builder().baseUrl("https://api.kiwoom.com");
        var delayedServer = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        var arrivals = new java.util.concurrent.CountDownLatch(3);
        var release = new java.util.concurrent.CountDownLatch(1);
        var delayedTokens = new KiwoomTokenProvider(builder.build(), "test", "test", java.time.Clock.systemUTC()) {
            @Override public String accessToken() {
                arrivals.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("토큰 대기 초과");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                return "test-token";
            }
        };
        var starts = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        delayedServer.expect(org.springframework.test.web.client.ExpectedCount.times(3),
                        requestTo("https://api.kiwoom.com/api/dostk/stkinfo"))
                .andExpect(request -> starts.add(System.nanoTime()))
                .andRespond(withSuccess("{\"return_code\":0,\"stk_cd\":\"005930\",\"cur_prc\":\"100\"}", MediaType.APPLICATION_JSON));
        var delayedClient = new KiwoomMarketClient(builder.build(), delayedTokens);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 3; i++) futures.add(workers.submit(() -> delayedClient.currentPrice("005930")));
            boolean arrived = arrivals.await(5, java.util.concurrent.TimeUnit.SECONDS);
            release.countDown();
            assertThat(arrived).isTrue();
            for (var future : futures) assertThat(future.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(100);
        } finally {
            release.countDown();
        }
        starts.sort(Long::compareTo);
        assertThat(starts).hasSize(3);
        for (int i = 1; i < starts.size(); i++) assertThat(starts.get(i) - starts.get(i - 1)).isGreaterThan(180_000_000L);
        delayedServer.verify();
    }

    @Test
    void requestsInitialCurrentPriceAndRejectsMissingOrNonPositivePrice() {
        server.expect(requestTo("https://api.kiwoom.com/api/dostk/stkinfo"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-id", "ka10001"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(content().json("{\"stk_cd\":\"005930\"}"))
                .andRespond(withSuccess("""
                        {"return_code":0,"stk_cd":"005930","cur_prc":"-69,000"}
                        """, MediaType.APPLICATION_JSON));
        for (String price : new String[]{"0", "invalid", "69000.5"}) {
            server.expect(requestTo("https://api.kiwoom.com/api/dostk/stkinfo"))
                    .andRespond(withSuccess("""
                            {"return_code":0,"stk_cd":"005930","cur_prc":"%s"}
                            """.formatted(price), MediaType.APPLICATION_JSON));
        }
        assertThat(client.currentPrice("005930")).isEqualTo(69000);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.currentPrice("005930")).isInstanceOf(IllegalStateException.class);
        }
        server.verify();
    }

    @Test
    void requestsKospiIndexWithBearerTokenAndParsesSignedNumbers() {
        server.expect(requestTo("https://api.kiwoom.com/api/dostk/sect"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-id", "ka20001"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(content().json("""
                        {"mrkt_tp":"0","inds_cd":"001"}
                        """))
                .andRespond(withSuccess("""
                        {"return_code":0,"cur_prc":"-2,817.42","pred_pre":"-12.50","flu_rt":"-0.44"}
                        """, MediaType.APPLICATION_JSON));
        var index = client.kospi();
        assertThat(index.value()).isEqualByComparingTo(new BigDecimal("2817.42"));
        assertThat(index.change()).isEqualByComparingTo(new BigDecimal("-12.50"));
        assertThat(index.changeRate()).isEqualByComparingTo(new BigDecimal("-0.44"));
        assertThat(index.fetchedAt()).isNotNull();
        server.verify();
    }

    @Test
    void rejectsProviderErrorAndMissingValueWithoutLeakingResponse() {
        server.expect(requestTo("https://api.kiwoom.com/api/dostk/sect"))
                .andRespond(withSuccess("""
                        {"return_code":3,"return_msg":"test-secret"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.kiwoom.com/api/dostk/sect"))
                .andRespond(withSuccess("""
                        {"return_code":0,"pred_pre":"+1","flu_rt":"+0.1"}
                        """, MediaType.APPLICATION_JSON));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(client::kospi).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("test-secret");
        }
        server.verify();
    }

    @Test
    void requestsStockInfoForOrderValidation() {
        server.expect(requestTo("https://api.kiwoom.com/api/dostk/stkinfo"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-id", "ka10100"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(content().json("{\"stk_cd\":\"005930\"}"))
                .andRespond(withSuccess("""
                        {"return_code":0,"code":"005930","marketCode":"0",
                         "state":"증거금20%|담보대출|신용가능","orderWarning":"0"}
                        """, MediaType.APPLICATION_JSON));
        var info = client.stockInfo("005930");
        assertThat(info.marketCode()).isEqualTo("0");
        assertThat(info.state()).contains("신용가능");
        server.verify();
    }
}
