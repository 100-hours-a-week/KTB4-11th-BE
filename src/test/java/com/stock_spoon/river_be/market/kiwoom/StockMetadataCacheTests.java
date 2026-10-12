package com.stock_spoon.river_be.market.kiwoom;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockMetadataCacheTests {
    private static final Map<String, Object> RESPONSE = Map.of("return_code", 0, "code", "005930", "name", "삼성전자",
            "marketCode", "0", "upName", "전기전자", "state", "거래정지", "orderWarning", "주의");

    @Test
    void sharedJsonContainsOnlyMetadataAndExpiresInSevenDays() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var cache = new StockMetadataCache(redis);
        assertThat(cache.load("005930", () -> RESPONSE)).containsEntry("name", "삼성전자").doesNotContainKeys("state", "orderWarning");
        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("quote:005930:metadata"), json.capture(), eq(Duration.ofDays(7)));
        assertThat(json.getValue()).doesNotContain("거래정지", "orderWarning");
        when(values.get("quote:005930:metadata")).thenReturn(json.getValue());
        assertThat(new StockMetadataCache(redis).load("005930", () -> { throw new AssertionError("cache miss"); }))
                .containsEntry("marketCode", "0");
    }

    @Test
    void overlappingCollectorRequestsShareOneLookup() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reads = new AtomicInteger();
        when(values.get(anyString())).thenAnswer(call -> { reads.incrementAndGet(); return null; });
        var cache = new StockMetadataCache(redis);
        var calls = new AtomicInteger();
        java.util.function.Supplier<Map<String, Object>> loader = () -> {
            calls.incrementAndGet(); entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            return RESPONSE;
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> cache.load("005930", loader));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> cache.load("005930", loader));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (reads.get() < 3 && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(reads.get()).isGreaterThanOrEqualTo(3);
            release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo(second.get(1, TimeUnit.SECONDS));
            assertThat(calls.get()).isEqualTo(1);
        } finally { release.countDown(); }
    }

    @Test
    void invalidResponseAndRedisFailureDoNotProduceCachedSuccess() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var cache = new StockMetadataCache(redis);
        assertThatThrownBy(() -> cache.load("005930", () -> Map.of("return_code", 3)))
                .isInstanceOf(IllegalStateException.class);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("Redis unavailable"));
        assertThatThrownBy(() -> cache.load("005930", () -> { throw new AssertionError("external fallback"); }))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void orderValidationAlwaysQueriesFreshStateInsteadOfMetadataCache() {
        var transport = mock(KiwoomRestTransport.class);
        var cache = mock(StockMetadataCache.class);
        when(transport.query(anyString(), anyString(), anyMap())).thenReturn(RESPONSE,
                Map.of("return_code", 0, "code", "005930", "marketCode", "0", "state", "정상"));
        var market = new KiwoomMarketClient(transport, cache);
        assertThat(market.stockInfo("005930").state()).isEqualTo("거래정지");
        assertThat(market.stockInfo("005930").state()).isEqualTo("정상");
        verify(transport, times(2)).query(eq("ka10100"), anyString(), anyMap());
        verifyNoInteractions(cache);
    }
}
