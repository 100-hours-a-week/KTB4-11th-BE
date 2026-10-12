package com.stock_spoon.river_be.market.kiwoom;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RedisCollectorHeartbeatTests {
    @Test
    void duplicateCollectorCannotStart() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(false);
        var heartbeat = new RedisCollectorHeartbeat(redis, true);
        assertThatThrownBy(heartbeat::start).isInstanceOf(IllegalStateException.class);
        assertThat(heartbeat.ownsLease()).isFalse();
    }

    @Test
    void lostLeaseIsNeverReacquired() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
        var heartbeat = new RedisCollectorHeartbeat(redis, true);
        heartbeat.start();
        heartbeat.renew(); // 소유자 조건부 갱신 실패
        heartbeat.renew();
        assertThat(heartbeat.ownsLease()).isFalse();
        verify(values, times(1)).setIfAbsent(anyString(), anyString(), any(java.time.Duration.class));
    }
}
