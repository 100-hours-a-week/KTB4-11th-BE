package com.stock_spoon.river_be.config;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 로컬 Valkey가 실행 중이고 명시적으로 활성화했을 때만 연결을 검증한다. */
@EnabledIfEnvironmentVariable(named = "REDIS_LIVE_TEST", matches = "true")
class RedisConnectionLiveTests {
    @Test
    void connectsUsingApplicationSettingsAndStoresExpiringValue() throws Exception {
        // 테스트 application.yaml에는 Redis 설정이 없으므로 운영 소스를 직접 읽는다.
        var settings = new YamlPropertySourceLoader().load("redis-main-settings",
                new FileSystemResource("src/main/resources/application.yaml")).getFirst();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
                .withUserConfiguration(RedisDeploymentConfiguration.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addLast(settings))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var redis = context.getBean(StringRedisTemplate.class);
                    String key = "quote:development:connection-check:" + UUID.randomUUID();
                    try {
                        redis.opsForValue().set(key, "ok", Duration.ofSeconds(60));
                        assertEquals("ok", redis.opsForValue().get(key));
                        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
                        assertNotNull(ttl);
                        assertTrue(ttl > 0 && ttl <= 60);
                    } finally {
                        redis.delete(key);
                    }
                    assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
                });
    }
}
