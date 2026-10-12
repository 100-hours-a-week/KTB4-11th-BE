package com.stock_spoon.river_be.market.kiwoom;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RedisTokenProviderTests {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    @Test
    void cacheIsReusedAcrossProviderRestartAndSixtySecondBoundaryRefreshes() {
        var cache = mock(RedisKiwoomTokenStore.class);
        var builder = RestClient.builder().baseUrl("https://api.kiwoom.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        when(cache.read()).thenReturn(Optional.of(new RedisKiwoomTokenStore.Token("cached", NOW.plusSeconds(61))));
        var client = builder.build();
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThat(new KiwoomTokenProvider(client, "app", "secret", clock, cache, () -> true).accessToken()).isEqualTo("cached");
        assertThat(new KiwoomTokenProvider(client, "app", "secret", clock, cache, () -> true).accessToken()).isEqualTo("cached");
        when(cache.read()).thenReturn(Optional.of(new RedisKiwoomTokenStore.Token("cached", NOW.plusSeconds(60))));
        server.expect(requestTo("https://api.kiwoom.com/oauth2/token"))
                .andRespond(withSuccess("{\"return_code\":0,\"token\":\"fresh\",\"token_type\":\"bearer\",\"expires_dt\":\"20261011100000\"}", MediaType.APPLICATION_JSON));
        assertThat(new KiwoomTokenProvider(client, "app", "secret", clock, cache, () -> true).accessToken()).isEqualTo("fresh");
        verify(cache).save(new RedisKiwoomTokenStore.Token("fresh", NOW.plusSeconds(3600)), NOW);
        server.verify();
    }

    @Test
    void ordinaryBeOrLostLeaseCannotReadIssueOrInvalidateToken() {
        var cache = mock(RedisKiwoomTokenStore.class);
        var client = mock(RestClient.class);
        var provider = new KiwoomTokenProvider(client, "app", "secret", Clock.fixed(NOW, ZoneOffset.UTC), cache, () -> false);
        assertThatThrownBy(provider::accessToken).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provider.invalidate("old")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(cache, client);
    }

    @Test
    void leaseLossDuringIssuanceDoesNotSaveOrReturnToken() {
        var cache = mock(RedisKiwoomTokenStore.class);
        when(cache.read()).thenReturn(Optional.empty());
        var owned = new AtomicBoolean(true);
        var builder = RestClient.builder().baseUrl("https://api.kiwoom.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.kiwoom.com/oauth2/token")).andRespond(request -> {
            owned.set(false);
            return withSuccess("{\"return_code\":0,\"token\":\"fresh\",\"token_type\":\"bearer\",\"expires_dt\":\"20261011100000\"}", MediaType.APPLICATION_JSON).createResponse(request);
        });
        var provider = new KiwoomTokenProvider(builder.build(), "app", "secret", Clock.fixed(NOW, ZoneOffset.UTC), cache, owned::get);
        assertThatThrownBy(provider::accessToken).isInstanceOf(IllegalStateException.class);
        verify(cache, never()).save(any(), any());
    }

    @Test
    void redisReadFailureDoesNotIssueTokenAsFallback() {
        var cache = mock(RedisKiwoomTokenStore.class);
        when(cache.read()).thenThrow(new IllegalStateException("Redis unavailable"));
        var client = mock(RestClient.class);
        var provider = new KiwoomTokenProvider(client, "app", "secret", Clock.fixed(NOW, ZoneOffset.UTC), cache, () -> true);
        assertThatThrownBy(provider::accessToken).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(client);
    }

    @Test
    void concurrentRestAndWebSocketRequestsIssueOnlyOneSharedToken() throws Exception {
        var cache = mock(RedisKiwoomTokenStore.class);
        var snapshot = new java.util.concurrent.atomic.AtomicReference<RedisKiwoomTokenStore.Token>();
        when(cache.read()).thenAnswer(call -> Optional.ofNullable(snapshot.get()));
        doAnswer(call -> { snapshot.set(call.getArgument(0)); return null; }).when(cache).save(any(), any());
        var builder = RestClient.builder().baseUrl("https://api.kiwoom.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.kiwoom.com/oauth2/token"))
                .andRespond(withSuccess("{\"return_code\":0,\"token\":\"fresh\",\"token_type\":\"bearer\",\"expires_dt\":\"20261011100000\"}", MediaType.APPLICATION_JSON));
        var provider = new KiwoomTokenProvider(builder.build(), "app", "secret", Clock.fixed(NOW, ZoneOffset.UTC), cache, () -> true);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var requests = java.util.stream.IntStream.range(0, 8)
                    .<java.util.concurrent.Callable<String>>mapToObj(index -> provider::accessToken).toList();
            for (var result : pool.invokeAll(requests)) assertThat(result.get()).isEqualTo("fresh");
        }
        verify(cache).save(any(), any());
        server.verify();
    }
}
