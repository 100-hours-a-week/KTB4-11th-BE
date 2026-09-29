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
