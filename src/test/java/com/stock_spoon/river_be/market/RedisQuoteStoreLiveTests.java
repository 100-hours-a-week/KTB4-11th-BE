package com.stock_spoon.river_be.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 명시적으로 활성화하면 로컬 개발 Valkey의 테스트 전용 종목을 사용한다. */
@EnabledIfEnvironmentVariable(named = "REDIS_LIVE_TEST", matches = "true")
class RedisQuoteStoreLiveTests {
    @Test
    void sharesExactQuotesBetweenTwoReadersAndRefreshesOneDayExpiry() {
        var connection = new LettuceConnectionFactory("localhost", 6380);
        connection.afterPropertiesSet();
        connection.start();
        var redis = new StringRedisTemplate(connection);
        var writer = new RedisQuoteStore(redis);
        var reader = new RedisQuoteStore(new StringRedisTemplate(connection));
        String code = "TST001";
        String priceKey = "quote:" + code + ":price";
        String bookKey = "quote:" + code + ":orderbook";
        var price = new StockPrice(code, new BigDecimal("123456789.123456789"),
                new BigDecimal("-500.25"), new BigDecimal("-0.37"),
                LocalTime.of(9, 1, 2), Instant.parse("2026-10-11T00:01:02.123456789Z"));
        var book = new OrderBook(code,
                List.of(new QuoteLevel(new BigDecimal("70000"), 12)),
                List.of(new QuoteLevel(new BigDecimal("69900"), 34)),
                price.tradeTime(), price.receivedAt());
        try {
            redis.delete(List.of(priceKey, bookKey));
            assertTrue(reader.latestPrice(code).isEmpty());
            assertTrue(reader.latestOrderBook(code).isEmpty());
            writer.savePrice(price);
            writer.saveOrderBook(book);
            assertEquals(price, reader.latestPrice(code).orElseThrow());
            assertEquals(book, reader.latestOrderBook(code).orElseThrow());
            assertTrue(redis.opsForValue().get(priceKey).startsWith("{"));
            assertDayExpiry(redis, priceKey);
            assertDayExpiry(redis, bookKey);

            redis.expire(priceKey, 30, TimeUnit.SECONDS);
            var updated = new StockPrice(code, new BigDecimal("71000"), price.change(),
                    price.changeRate(), price.tradeTime(), price.receivedAt().plusSeconds(1));
            writer.savePrice(updated);
            assertEquals(updated, reader.latestPrice(code).orElseThrow());
            assertDayExpiry(redis, priceKey);
            assertEquals(book, reader.latestOrderBook(code).orElseThrow());

            redis.opsForValue().set(priceKey, "invalid-json");
            assertThrows(RuntimeException.class, () -> reader.latestPrice(code));
            assertThrows(IllegalArgumentException.class, () -> reader.latestPrice("*"));
        } finally {
            try {
                redis.delete(List.of(priceKey, bookKey));
            } finally {
                connection.destroy();
            }
        }
    }

    private static void assertDayExpiry(StringRedisTemplate redis, String key) {
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 86340 && ttl <= 86400, "TTL: " + ttl);
    }
}
