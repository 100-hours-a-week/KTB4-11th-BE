package com.stock_spoon.river_be.market;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 요청 키와 만료 순서 색인은 같은 Cluster 슬롯에서 함께 갱신한다. */
@Component
public class RedisSubscriptionDemand {
    private static final String INDEX = "coord:kiwoom:{accepting}:index";
    private static final String NOW = "local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000); ";
    private static final String UPDATE = "redis.call('ZADD',KEYS[2],now+30000,ARGV[1]..':'..ARGV[2]); redis.call('EXPIRE',KEYS[2],60); return 1";
    private static final DefaultRedisScript<Long> RETAIN = new DefaultRedisScript<>(NOW
            + "redis.call('SET',KEYS[1],ARGV[2],'PX',30000); " + UPDATE, Long.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(NOW
            + "if redis.call('GET',KEYS[1]) ~= ARGV[2] then return 0 end; "
            + "redis.call('PEXPIRE',KEYS[1],30000); " + UPDATE, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "redis.call('DEL',KEYS[1]); redis.call('ZREM',KEYS[2],ARGV[1]..':'..ARGV[2]); return 1", Long.class);
    private static final DefaultRedisScript<List> SYMBOLS = new DefaultRedisScript<>(NOW
            + "redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now); return redis.call('ZRANGE',KEYS[1],0,-1)", List.class);
    private final StringRedisTemplate redis;

    public RedisSubscriptionDemand(StringRedisTemplate redis) { this.redis = redis; }

    public void retain(String id, String code) {
        validate(id, code);
        if (!Long.valueOf(1).equals(redis.execute(RETAIN, List.of(requestKey(id), INDEX), id, code))) {
            throw new IllegalStateException("접수 구독 수요를 저장하지 못했습니다.");
        }
    }

    public void renew(String id, String code) {
        validate(id, code);
        if (!Long.valueOf(1).equals(redis.execute(RENEW, List.of(requestKey(id), INDEX), id, code))) {
            throw new IllegalStateException("접수 구독 수요가 만료되었습니다.");
        }
    }

    public void assertRequest(String id, String code) {
        validate(id, code);
        if (!code.equals(redis.opsForValue().get(requestKey(id)))) {
            throw new IllegalStateException("접수 구독 수요가 만료되었습니다.");
        }
    }

    public void release(String id, String code) {
        validate(id, code);
        redis.execute(RELEASE, List.of(requestKey(id), INDEX), id, code);
    }

    public Set<String> symbols() {
        List<?> members = redis.execute(SYMBOLS, List.of(INDEX));
        if (members == null) throw new IllegalStateException("접수 구독 수요를 확인하지 못했습니다.");
        return members.stream().map(Object::toString).map(member -> member.substring(member.lastIndexOf(':') + 1))
                .collect(Collectors.toSet());
    }

    static String requestKey(String id) {
        if (id == null || !id.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("요청 ID를 확인하세요.");
        return "coord:kiwoom:{accepting}:request:" + id;
    }

    private static void validate(String id, String code) {
        requestKey(id);
        if (code == null || !code.matches("[0-9A-Z]{6}")) throw new IllegalArgumentException("종목코드를 확인하세요.");
    }
}
