package com.stock_spoon.river_be.market.kiwoom;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 고정 담당만 시작 시 소유권을 얻는다. 만료 후에는 재시작 전까지 재획득하지 않는다. */
@Component
public class RedisCollectorHeartbeat {
    static final String KEY = "coord:kiwoom:collector";
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('EXPIRE',KEYS[1],15) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final String owner = UUID.randomUUID().toString();
    private volatile boolean active;

    public RedisCollectorHeartbeat(StringRedisTemplate redis,
            @Value("${kiwoom.collector.enabled:false}") boolean enabled) {
        this.redis = redis;
        this.enabled = enabled;
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(KEY, owner, Duration.ofSeconds(15)))) {
            throw new IllegalStateException("이미 실행 중인 키움 수집 담당이 있습니다.");
        }
        active = true;
    }

    @Scheduled(fixedDelay = 5000, scheduler = "collectorLeaseScheduler")
    public void renew() {
        if (!active) return;
        try {
            if (!Long.valueOf(1).equals(redis.execute(RENEW, List.of(KEY), owner))) active = false;
        } catch (RuntimeException error) {
            active = false;
        }
    }

    public boolean ownsLease() {
        if (!active) return false;
        try {
            if (owner.equals(redis.opsForValue().get(KEY))) return true;
        } catch (RuntimeException error) {
            // Redis 확인 실패는 담당 유효성 실패로 처리한다.
        }
        active = false;
        return false;
    }

    public String ownerId() { return owner; }

    @PreDestroy
    public void close() {
        active = false;
        if (!enabled) return;
        try {
            redis.execute(RELEASE, List.of(KEY), owner);
        } catch (RuntimeException error) {
            // 종료 시 Redis가 없으면 TTL로 정리한다.
        }
    }
}
