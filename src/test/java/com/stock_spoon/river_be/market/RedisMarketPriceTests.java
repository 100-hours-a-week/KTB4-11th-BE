package com.stock_spoon.river_be.market;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisMarketPriceTests {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private RedisMarketPrice store() {
        when(redis.opsForValue()).thenReturn(values);
        return new RedisMarketPrice(redis);
    }

    @Test
    void readsOriginalAiSnapshotIncludingPreviousTradingDayWithoutWritingOrRepublishing() {
        var store = store();
        when(values.get("price:005930")).thenReturn("""
                {"code":"005930","price":70000,"timestamp":"2026-10-16T15:30:00+09:00"}
                """);
        assertThat(store.currentPrice("005930")).isEqualTo(70000);
        verify(values).get("price:005930");
        verifyNoMoreInteractions(values);
    }

    @Test
    void missingMalformedWrongStockAndInvalidPricesFailWithoutFallback() {
        var store = store();
        for (String raw : java.util.Arrays.asList(null, "broken", "{}",
                "{\"code\":\"000660\",\"price\":70000,\"timestamp\":\"2026-10-16T15:30:00+09:00\"}",
                "{\"code\":\"005930\",\"price\":0,\"timestamp\":\"2026-10-16T15:30:00+09:00\"}",
                "{\"code\":\"005930\",\"price\":-1,\"timestamp\":\"2026-10-16T15:30:00+09:00\"}",
                "{\"code\":\"005930\",\"price\":1.5,\"timestamp\":\"2026-10-16T15:30:00+09:00\"}",
                "{\"code\":\"005930\",\"price\":9223372036854775808,\"timestamp\":\"2026-10-16T15:30:00+09:00\"}",
                "{\"code\":\"005930\",\"price\":\"70000\",\"timestamp\":\"2026-10-16T15:30:00+09:00\"}",
                "{\"code\":\"005930\",\"price\":70000,\"timestamp\":\"invalid\"}")) {
            when(values.get("price:005930")).thenReturn(raw);
            assertThatThrownBy(() -> store.currentPrice("005930")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void redisFailureIsReportedAndInvalidCodeDoesNotAccessRedis() {
        var store = store();
        when(values.get("price:005930")).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        assertThatThrownBy(() -> store.currentPrice("005930")).isInstanceOf(IllegalStateException.class);
        clearInvocations(redis, values);
        assertThatThrownBy(() -> store.currentPrice("bad")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(redis, values);
    }
}
