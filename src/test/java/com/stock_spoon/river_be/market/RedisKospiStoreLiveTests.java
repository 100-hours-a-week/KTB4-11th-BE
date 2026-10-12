package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient.KospiIndex;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "REDIS_LIVE_TEST", matches = "true")
class RedisKospiStoreLiveTests {
    @Test
    void recentExpiryDoesNotRemoveSevenDayLastSuccess() {
        var connections = new LettuceConnectionFactory("localhost", 6380);
        connections.afterPropertiesSet(); connections.start();
        var redis = new StringRedisTemplate(connections);
        String prefix = "quote:development:kospi:" + UUID.randomUUID();
        var writer = new RedisKospiStore(redis, prefix);
        var reader = new RedisKospiStore(new StringRedisTemplate(connections), prefix);
        var value = new KospiIndex(new BigDecimal("2650.123456789"), new BigDecimal("-5.25"),
                new BigDecimal("-0.12345"), Instant.now());
        try {
            writer.save(value);
            assertThat(reader.read(true)).contains(value);
            assertThat(reader.read(false)).contains(value);
            assertThat(redis.getExpire(prefix + ":recent", TimeUnit.SECONDS)).isBetween(50L, 60L);
            assertThat(redis.getExpire(prefix + ":last-success", TimeUnit.SECONDS)).isBetween(604740L, 604800L);
            redis.expire(prefix + ":recent", 0, TimeUnit.SECONDS);
            assertThat(reader.read(true)).isEmpty();
            assertThat(reader.read(false)).contains(value);
            redis.opsForValue().set(prefix + ":last-success", "broken");
            assertThatThrownBy(() -> reader.read(false)).isInstanceOf(IllegalStateException.class);
        } finally {
            try {
                redis.delete(prefix + ":recent");
                redis.delete(prefix + ":last-success");
            } finally { connections.destroy(); }
        }
    }
}
