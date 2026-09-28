package com.stock_spoon.river_be.market.kiwoom;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KiwoomTokenProviderTests {
    private MockRestServiceServer server;
    private KiwoomTokenProvider provider;
    private Clock clock;
    private RestClient client;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl("https://api.kiwoom.com");
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-09-26T00:00:00Z"));
        provider = new KiwoomTokenProvider(client, "test-key", "test-secret", clock);
    }

    @Test
    void rejectedTokenIsRefreshedButOldRejectionDoesNotDiscardNewToken() {
        expectToken("first", "20260927090000");
        expectToken("second", "20260927090000");
        assertThat(provider.accessToken()).isEqualTo("first");
        provider.invalidate("first");
        assertThat(provider.accessToken()).isEqualTo("second");
        provider.invalidate("first");
        assertThat(provider.accessToken()).isEqualTo("second");
        server.verify();
    }

    private void expectToken(String token, String expiry) {
        server.expect(requestTo("https://api.kiwoom.com/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"grant_type":"client_credentials","appkey":"test-key","secretkey":"test-secret"}
                        """))
                .andRespond(withSuccess("""
                        {"return_code":0,"token_type":"bearer","token":"%s","expires_dt":"%s"}
                        """.formatted(token, expiry), MediaType.APPLICATION_JSON));
    }

    @Test
    void reusesTokenAndRefreshesAtOneMinuteBoundaryUsingKoreanExpiry() {
        expectToken("first", "20260926091000");
        expectToken("second", "20260926092000");
        assertThat(provider.accessToken()).isEqualTo("first");
        when(clock.instant()).thenReturn(Instant.parse("2026-09-26T00:08:59Z"));
        assertThat(provider.accessToken()).isEqualTo("first");
        when(clock.instant()).thenReturn(Instant.parse("2026-09-26T00:09:00Z"));
        assertThat(provider.accessToken()).isEqualTo("second");
        server.verify();
    }

    @Test
    void concurrentRequestsIssueOneToken() throws Exception {
        expectToken("shared", "20260927090000");
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0, 16)
                    .<java.util.concurrent.Callable<String>>mapToObj(i -> provider::accessToken).toList();
            for (var result : executor.invokeAll(tasks)) {
                assertThat(result.get()).isEqualTo("shared");
            }
        }
        server.verify();
    }

    @Test
    void missingCredentialsNeverCallProvider() {
        var missing = new KiwoomTokenProvider(client, "", "", clock);
        assertThatThrownBy(missing::accessToken)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("설정이 필요");
        server.verify();
    }

    @Test
    void rejectsBusinessErrorWithoutExposingProviderBodyAndCanRetry() {
        server.expect(requestTo("https://api.kiwoom.com/oauth2/token"))
                .andRespond(withSuccess("""
                        {"return_code":1,"return_msg":"test-secret"}
                        """, MediaType.APPLICATION_JSON));
        expectToken("retry", "20260927090000");
        assertThatThrownBy(provider::accessToken).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("test-secret").hasNoCause();
        assertThat(provider.accessToken()).isEqualTo("retry");
        server.verify();
    }

    @Test
    void identifiesDeviceAuthenticationFailureWithoutLeakingProviderMessage() {
        server.expect(requestTo("https://api.kiwoom.com/oauth2/token"))
                .andRespond(withSuccess("""
                        {"return_code":3,"return_msg":"인증 실패[8050:test-secret]"}
                        """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(provider::accessToken).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("8050").hasMessageContaining("허용 IP")
                .hasMessageNotContaining("test-secret");
        server.verify();
    }
    @Test
    void rejectsExpiredAndMalformedResponses() {
        expectToken("expired", "20260926085959");
        expectToken("bad-date", "invalid");
        server.expect(requestTo("https://api.kiwoom.com/oauth2/token"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(provider::accessToken)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("유효하지");
        }
        server.verify();
    }

    @Test
    void hidesHttpErrorBodyAndTimeoutDetails() {
        server.expect(requestTo("https://api.kiwoom.com/oauth2/token"))
                .andRespond(withUnauthorizedRequest().body("test-secret"));
        server.expect(requestTo("https://api.kiwoom.com/oauth2/token"))
                .andRespond(withException(new java.net.SocketTimeoutException("test-key")));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(provider::accessToken).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("통신에 실패").hasMessageNotContaining("test-secret")
                    .hasMessageNotContaining("test-key").hasNoCause();
        }
        server.verify();
    }
}
