package com.stock_spoon.river_be.market.kiwoom;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "REDIS_LIVE_TEST", matches = "true")
class RedisTokenStoreLiveTests {
    @Test
    void sharesTokenWithSafetyTtlAndDoesNotInvalidateNewerToken() {
        var connections = new LettuceConnectionFactory("localhost", 6380);
        connections.afterPropertiesSet(); connections.start();
        var redis = new StringRedisTemplate(connections);
        String key = "token:kiwoom:development-test:" + UUID.randomUUID();
        var writer = new RedisKiwoomTokenStore(redis, "test-app", key);
        var restarted = new RedisKiwoomTokenStore(new StringRedisTemplate(connections), "test-app", key);
        Instant now = Instant.now();
        var old = new RedisKiwoomTokenStore.Token("dummy-old", now.plusSeconds(3600));
        var fresh = new RedisKiwoomTokenStore.Token("dummy-new", now.plusSeconds(3600));
        try {
            writer.save(old, now);
            assertThat(restarted.read()).contains(old);
            assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(3530L, 3540L);
            assertThat(redis.opsForValue().get(key)).doesNotContain("test-app");
            assertThat(new RedisKiwoomTokenStore(redis, "different-app", key).read()).isEmpty();
            writer.save(fresh, now);
            restarted.invalidate("dummy-old");
            assertThat(restarted.read()).contains(fresh);
            restarted.invalidate("dummy-new");
            assertThat(writer.read()).isEmpty();
            redis.opsForValue().set(key, "{broken}");
            assertThatThrownBy(writer::read).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("broken");
        } finally {
            try { redis.delete(key); } finally { connections.destroy(); }
        }
    }
}
