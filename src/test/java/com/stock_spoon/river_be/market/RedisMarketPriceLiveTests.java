package com.stock_spoon.river_be.market;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "REDIS_LIVE_TEST", matches = "true")
class RedisMarketPriceLiveTests {
    @Test
    void readsAiOriginalWithoutCollectorAndDoesNotChangeSnapshot() {
        var connection = new LettuceConnectionFactory("localhost", 6380);
        connection.afterPropertiesSet();
        connection.start();
        var redis = new StringRedisTemplate(connection);
        String key = "price:TST011";
        String raw = """
                {"code":"TST011","price":70000,"timestamp":"2026-10-16T15:30:00+09:00"}
                """;
        boolean created = false;
        try {
            created = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, raw, Duration.ofMinutes(1)));
            assertThat(created).as("기존 테스트 값을 덮어쓰지 않음").isTrue();
            var prices = new RedisMarketPrice(redis);
            assertThat(prices.currentPrice("TST011")).isEqualTo(70000);
            assertThat(redis.opsForValue().get(key)).isEqualTo(raw);
            redis.delete(key);
            assertThatThrownBy(() -> prices.currentPrice("TST011")).isInstanceOf(IllegalStateException.class);
        } finally {
            if (created) redis.delete(key);
            connection.destroy();
        }
    }
}
