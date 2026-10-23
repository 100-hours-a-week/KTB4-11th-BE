package com.stock_spoon.river_be.market;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 평가용 마지막 가격 조회. 저장된 스냅샷을 체결 이벤트로 만들지 않는다. */
@Component
public class RedisMarketPrice {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RedisMarketPrice.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final StringRedisTemplate redis;

    public RedisMarketPrice(StringRedisTemplate redis) { this.redis = redis; }

    public long currentPrice(String code) {
        if (code == null || !code.matches("[0-9A-Z]{6}")) {
            throw new IllegalArgumentException("종목코드를 확인하세요.");
        }
        try {
            String raw = redis.opsForValue().get("price:" + code);
            if (raw == null) throw new IllegalStateException("저장된 현재가가 없습니다.");
            var node = JSON.readTree(raw);
            if (!code.equals(node.path("code").asText()) || !node.path("price").isNumber()) {
                throw new IllegalArgumentException("현재가 형식 오류");
            }
            OffsetDateTime.parse(node.path("timestamp").asText());
            long price = new BigDecimal(node.path("price").asText()).longValueExact();
            if (price <= 0) throw new IllegalArgumentException("현재가 범위 오류");
            return price;
        } catch (RuntimeException error) {
            log.warn("event=redis_market_price_failed stage=current_price_lookup stockCode={} causeType={} nestedCauseType={}",
                    code, error.getClass().getSimpleName(),
                    error.getCause() == null ? "none" : error.getCause().getClass().getSimpleName());
            throw new IllegalStateException("Redis 현재가를 조회할 수 없습니다.");
        }
    }
}
