package com.stock_spoon.river_be.market.kiwoom;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.json.JsonMapper;

/** 키움 앱의 토큰만 저장한다. 앱 키·시크릿 원문은 저장하지 않는다. */
public class RedisKiwoomTokenStore {
    private final String key;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DefaultRedisScript<Long> INVALIDATE = new DefaultRedisScript<>(
            "local raw=redis.call('GET',KEYS[1]); if not raw then return 0 end; "
            + "local v=cjson.decode(raw); if v.token==ARGV[1] and v.appFingerprint==ARGV[2] "
            + "then return redis.call('DEL',KEYS[1]) else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    private final String fingerprint;

    public RedisKiwoomTokenStore(StringRedisTemplate redis, String appKey) {
        this(redis, appKey, "token:kiwoom:access");
    }

    RedisKiwoomTokenStore(StringRedisTemplate redis, String appKey, String key) {
        this.redis = redis;
        this.key = key;
        try {
            fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(appKey.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public Optional<Token> read() {
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null) return Optional.empty();
            var value = JSON.readValue(raw, StoredToken.class);
            if (value == null || value.token() == null || value.token().isBlank() || value.expiresAt() == null
                    || value.appFingerprint() == null) throw new IllegalStateException();
            if (!fingerprint.equals(value.appFingerprint())) return Optional.empty();
            return Optional.of(new Token(value.token(), value.expiresAt()));
        } catch (RuntimeException error) { throw unavailable(); }
    }

    public void save(Token token, Instant now) {
        Duration ttl = Duration.between(now, token.expiresAt().minusSeconds(60));
        if (ttl.isNegative() || ttl.isZero()) throw unavailable();
        try {
            redis.opsForValue().set(key, JSON.writeValueAsString(new StoredToken(token.value(), token.expiresAt(), fingerprint)), ttl);
        } catch (RuntimeException error) { throw unavailable(); }
    }

    public void invalidate(String rejected) {
        try { redis.execute(INVALIDATE, List.of(key), rejected, fingerprint); }
        catch (RuntimeException error) { throw unavailable(); }
    }

    private IllegalStateException unavailable() { return new IllegalStateException("키움 토큰 저장소를 사용할 수 없습니다."); }
    private record StoredToken(String token, Instant expiresAt, String appFingerprint) {}
    public record Token(String value, Instant expiresAt) {
        @Override public String toString() { return "Token[redacted]"; }
    }
}
