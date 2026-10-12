package com.stock_spoon.river_be.market.kiwoom;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "REDIS_LIVE_TEST", matches = "true")
class StockMetadataCacheLiveTests {
    @Test
    void secondBeReadsSharedMetadataWithSevenDayTtl() {
        var connection = new LettuceConnectionFactory("localhost", 6380);
        connection.afterPropertiesSet();
        connection.start();
        var redis = new StringRedisTemplate(connection);
        String key = "quote:TST006:metadata";
        try {
            redis.delete(key);
            var first = new StockMetadataCache(redis);
            var second = new StockMetadataCache(new StringRedisTemplate(connection));
            first.load("TST006", () -> Map.of("return_code", 0, "code", "TST006", "name", "테스트 종목",
                    "marketCode", "0", "upName", "테스트 업종", "state", "거래정지"));
            assertThat(second.load("TST006", () -> { throw new AssertionError("duplicate REST"); }))
                    .containsEntry("name", "테스트 종목").doesNotContainKey("state");
            assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(604740L, 604800L);
            assertThat(redis.opsForValue().get(key)).doesNotContain("거래정지");
            redis.opsForValue().set(key, "broken");
            assertThatThrownBy(() -> second.load("TST006", () -> { throw new AssertionError("fallback"); }))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            try { redis.delete(key); } finally { connection.destroy(); }
        }
    }
}
