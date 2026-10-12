package com.stock_spoon.river_be.market.kiwoom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "REDIS_LIVE_TEST", matches = "true")
class RedisCollectorHeartbeatLiveTests {
    @Test
    void renewsOnlyOwnersLeaseAndNeverDeletesAnotherOwner() {
        var connection = new LettuceConnectionFactory("localhost", 6380);
        connection.afterPropertiesSet();
        connection.start();
        var redis = new StringRedisTemplate(connection);
        var first = new RedisCollectorHeartbeat(redis, true);
        var second = new RedisCollectorHeartbeat(redis, true);
        String replacement = java.util.UUID.randomUUID().toString();
        try {
            // 실행 중인 개발 BE의 소유권을 덮어쓰지 않는다.
            assertThat(redis.hasKey(RedisCollectorHeartbeat.KEY)).isFalse();
            first.start();
            assertThat(first.ownsLease()).isTrue();
            assertThat(redis.getExpire(RedisCollectorHeartbeat.KEY)).isBetween(1L, 15L);
            assertThatThrownBy(second::start).isInstanceOf(IllegalStateException.class);
            second.close();
            assertThat(first.ownsLease()).isTrue();
            redis.expire(RedisCollectorHeartbeat.KEY, java.time.Duration.ofSeconds(2));
            first.renew();
            assertThat(redis.getExpire(RedisCollectorHeartbeat.KEY)).isBetween(10L, 15L);
            redis.opsForValue().set(RedisCollectorHeartbeat.KEY, replacement, java.time.Duration.ofSeconds(15));
            first.renew();
            assertThat(first.ownsLease()).isFalse();
            first.close();
            assertThat(redis.opsForValue().get(RedisCollectorHeartbeat.KEY)).isEqualTo(replacement);
        } finally {
            first.close();
            second.close();
            if (replacement.equals(redis.opsForValue().get(RedisCollectorHeartbeat.KEY))) redis.delete(RedisCollectorHeartbeat.KEY);
            connection.destroy();
        }
    }
}
