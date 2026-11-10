package com.stock_spoon.river_be.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/** Atomic fixed-window request counters scoped to a verified end-user ID. */
public final class UserRateLimitService {
    private static final String KEY_PREFIX = "ratelimit:v1:user:";
    private static final RedisScript<List> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            local ttl = redis.call('PTTL', KEYS[1])
            if count == 1 or ttl < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
              ttl = redis.call('PTTL', KEYS[1])
            end
            return {count, ttl}
            """, List.class);

    private final StringRedisTemplate redis;
    private final int maxRequests;
    private final long windowMillis;
    private final Mode mode;
    private final Counter exceededRequests;
    private final Counter blockedRequests;
    private final Counter redisErrors;

    UserRateLimitService(StringRedisTemplate redis, int maxRequests, Duration window, Mode mode,
            MeterRegistry meterRegistry) {
        this.redis = redis;
        this.maxRequests = maxRequests;
        this.windowMillis = window.toMillis();
        this.mode = mode;
        this.exceededRequests = Counter.builder("security.user_rate_limit.exceeded")
                .description("Authenticated API requests over the configured per-user rate limit")
                .register(meterRegistry);
        this.blockedRequests = Counter.builder("security.user_rate_limit.blocked")
                .description("Authenticated API requests blocked by the per-user rate limiter")
                .register(meterRegistry);
        this.redisErrors = Counter.builder("security.user_rate_limit.redis_errors")
                .description("Per-user rate-limit checks that failed to reach Valkey")
                .register(meterRegistry);
    }

    Decision check(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        String key = KEY_PREFIX + userId + ":all";
        try {
            List<?> result = redis.execute(INCREMENT_SCRIPT, List.of(key), Long.toString(windowMillis));
            if (result == null || result.size() < 2) {
                throw new IllegalStateException("Valkey rate-limit script returned an invalid result");
            }
            long count = asLong(result.get(0));
            long ttlMillis = asLong(result.get(1));
            long retryAfterSeconds = ttlMillis > 0 ? Math.max(1, (ttlMillis + 999) / 1000)
                    : Math.max(1, (windowMillis + 999) / 1000);
            boolean allowed = count <= maxRequests;
            if (!allowed) {
                exceededRequests.increment();
                if (mode == Mode.BLOCK) blockedRequests.increment();
            }
            return new Decision(allowed, mode == Mode.OBSERVE, retryAfterSeconds, count, maxRequests, windowMillis);
        } catch (RuntimeException error) {
            redisErrors.increment();
            throw error;
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof byte[] bytes) return Long.parseLong(new String(bytes, StandardCharsets.UTF_8));
        return Long.parseLong(String.valueOf(value));
    }

    enum Mode {
        OBSERVE,
        BLOCK;

        static Mode from(String value) {
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("RATE_LIMIT_MODE는 observe 또는 block이어야 합니다.", invalid);
            }
        }
    }

    record Decision(boolean allowed, boolean observeOnly, long retryAfterSeconds,
            long requestCount, int maxRequests, long windowMillis) {}
}
