package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient.KospiIndex;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RedisKospiStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final StringRedisTemplate redis;
    private final String prefix;

    @org.springframework.beans.factory.annotation.Autowired
    public RedisKospiStore(StringRedisTemplate redis) { this(redis, "quote:kospi"); }

    RedisKospiStore(StringRedisTemplate redis, String prefix) {
        this.redis = redis;
        this.prefix = prefix;
    }

    public Optional<KospiIndex> read(boolean recent) {
        try {
            String raw = redis.opsForValue().get(prefix + (recent ? ":recent" : ":last-success"));
            if (raw == null) return Optional.empty();
            var value = JSON.readValue(raw, KospiIndex.class);
            validate(value);
            return Optional.of(value);
        } catch (RuntimeException error) { throw unavailable(); }
    }

    public void save(KospiIndex value) {
        validate(value);
        try {
            String raw = JSON.writeValueAsString(value);
            // 먼저 장외용 값을 확보한다. 두 키는 서로 다른 클러스터 슬롯에 있을 수 있다.
            redis.opsForValue().set(prefix + ":last-success", raw, Duration.ofDays(7));
            redis.opsForValue().set(prefix + ":recent", raw, Duration.ofSeconds(60));
        } catch (RuntimeException error) { throw unavailable(); }
    }

    private void validate(KospiIndex value) {
        if (value == null || value.value() == null || value.value().signum() <= 0 || value.change() == null
                || value.changeRate() == null || value.fetchedAt() == null) throw unavailable();
    }
    private IllegalStateException unavailable() { return new IllegalStateException("코스피 지수 저장소를 사용할 수 없습니다."); }
}
