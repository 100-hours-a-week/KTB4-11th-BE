package com.stock_spoon.river_be.market.kiwoom;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 장기 보관 가능한 종목 속성만 저장한다. 주문 상태 검증에는 사용하지 않는다. */
@Component
public class StockMetadataCache {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StockMetadataCache.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final StringRedisTemplate redis;
    // 조회 중인 작업만 유지한다. 결과는 Redis에 저장하고 작업은 성공·실패 모두 제거한다.
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> loading = new ConcurrentHashMap<>();

    public StockMetadataCache(StringRedisTemplate redis) { this.redis = redis; }

    public Map<String, Object> load(String code, Supplier<Map<String, Object>> loader) {
        if (code == null || !code.matches("[0-9A-Z]{6}")) throw new IllegalArgumentException("종목코드를 확인하세요.");
        var cached = read(code);
        if (cached != null) return cached;
        var own = new CompletableFuture<Map<String, Object>>();
        var existing = loading.putIfAbsent(code, own);
        if (existing != null) {
            try { return existing.join(); }
            catch (java.util.concurrent.CompletionException error) {
                log.warn("event=stock_metadata_failed stage=shared_load_wait stockCode={} causeType={}",
                        code, error.getCause() == null ? error.getClass().getSimpleName() : error.getCause().getClass().getSimpleName());
                throw new IllegalStateException("종목정보 확보에 실패했습니다.");
            }
        }
        try {
            cached = read(code);
            if (cached == null) {
                var raw = loader.get();
                if (raw == null || !"0".equals(String.valueOf(raw.get("return_code")))) throw invalid();
                var value = new Metadata((String) raw.get("code"), (String) raw.get("name"),
                        (String) raw.get("marketCode"), raw.get("upName") == null ? "" : (String) raw.get("upName"), Instant.now());
                validate(code, value);
                redis.opsForValue().set("quote:" + code + ":metadata", JSON.writeValueAsString(value), Duration.ofDays(7));
                cached = fields(value);
            }
            own.complete(cached);
            return cached;
        } catch (RuntimeException | Error error) {
            log.warn("event=stock_metadata_failed stage=load_and_cache stockCode={} causeType={}",
                    code, error.getClass().getSimpleName());
            own.completeExceptionally(error);
            if (error instanceof ClassCastException) throw invalid();
            if (error instanceof org.springframework.dao.DataAccessException) throw unavailable();
            throw error;
        } finally { loading.remove(code, own); }
    }

    private Map<String, Object> read(String code) {
        String raw;
        try { raw = redis.opsForValue().get("quote:" + code + ":metadata"); }
        catch (org.springframework.dao.DataAccessException error) {
            log.warn("event=stock_metadata_failed stage=cache_read stockCode={} causeType={}", code, error.getClass().getSimpleName());
            throw unavailable();
        }
        if (raw == null) return null;
        Metadata value;
        try { value = JSON.readValue(raw, Metadata.class); }
        catch (RuntimeException error) {
            log.warn("event=stock_metadata_failed stage=cache_decode stockCode={} causeType={}", code, error.getClass().getSimpleName());
            throw invalid();
        }
        validate(code, value);
        return fields(value);
    }

    private void validate(String code, Metadata value) {
        if (value == null || !code.equals(value.stockCode()) || value.stockName() == null || value.stockName().isBlank()
                || value.marketCode() == null || value.marketCode().isBlank() || value.sector() == null || value.fetchedAt() == null) throw invalid();
    }

    private Map<String, Object> fields(Metadata value) {
        return Map.of("return_code", 0, "code", value.stockCode(), "name", value.stockName(),
                "marketCode", value.marketCode(), "upName", value.sector());
    }

    private IllegalStateException invalid() { return new IllegalStateException("종목정보 캐시 형식이 올바르지 않습니다."); }
    private IllegalStateException unavailable() { return new IllegalStateException("종목정보 저장소를 사용할 수 없습니다."); }
    public record Metadata(String stockCode, String stockName, String marketCode, String sector, Instant fetchedAt) {}
}
