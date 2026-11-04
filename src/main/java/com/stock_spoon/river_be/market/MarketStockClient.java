package com.stock_spoon.river_be.market;

import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 시세 서버 응답을 기존 종목정보 캐시·서비스가 읽는 필드로 변환한다. */
public final class MarketStockClient {
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};
    private final RestClient client;

    public MarketStockClient(RestClient client) { this.client = client; }

    /** 공급 서버 일봉은 UTC 자정의 거래일을 사용하며 수정주가로 수집된다. */
    public long closingPrice(String code, java.time.LocalDate date) {
        if (code == null || !code.matches("[0-9A-Z]{6}") || date == null) {
            throw new IllegalArgumentException("종목코드와 거래일을 확인하세요.");
        }
        String cursor = null;
        var seen = new java.util.HashSet<String>();
        // 잘못된 공급 응답으로 정산 요청이 무한히 페이지를 조회하지 않게 제한한다.
        for (int page = 0; page < 100; page++) {
            String next = cursor;
            Map<String, Object> raw;
            try {
                raw = client.get().uri(uri -> {
                    var builder = uri.path("/bars/{code}").queryParam("timeframe", "1d").queryParam("limit", 1000);
                    if (next != null) builder.queryParam("cursor", "{cursor}");
                    return next == null ? builder.build(code) : builder.build(code, next);
                }).retrieve().body(JSON);
            } catch (RestClientException error) {
                throw new IllegalStateException("시세 서버 종가 조회에 실패했습니다.");
            }
            if (raw == null || !(raw.get("items") instanceof java.util.List<?> rows)
                    || !raw.containsKey("next_cursor")) throw invalidBars();
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> row) || !(row.get("timestamp") instanceof String timestamp)) throw invalidBars();
                try {
                    var day = java.time.OffsetDateTime.parse(timestamp).withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDate();
                    if (date.equals(day)) {
                        if (!(row.get("close") instanceof Number value)) throw invalidBars();
                        long price = new java.math.BigDecimal(value.toString()).longValueExact();
                        if (price <= 0) throw invalidBars();
                        return price;
                    }
                } catch (java.time.DateTimeException | ArithmeticException error) {
                    throw invalidBars();
                }
            }
            Object value = raw.get("next_cursor");
            if (value == null) break;
            if (!(value instanceof String text) || text.isBlank() || !seen.add(text)) throw invalidBars();
            cursor = text;
        }
        throw new IllegalStateException("해당 거래일의 종가를 확보할 수 없습니다.");
    }

    private IllegalStateException invalidBars() {
        return new IllegalStateException("시세 서버 일봉 응답 형식이 올바르지 않습니다.");
    }

    public Map<String, Object> stockInfo(String code) {
        if (code == null || !code.matches("[0-9A-Z]{6}")) {
            throw new IllegalArgumentException("종목코드를 확인하세요.");
        }
        Map<String, Object> raw;
        try {
            raw = client.get().uri("/stocks/{code}", code).retrieve().body(JSON);
        } catch (RestClientException error) {
            throw new IllegalStateException("시세 서버 종목정보 조회에 실패했습니다.");
        }
        if (raw == null || !code.equals(raw.get("code"))) throw invalid();
        String name = field(raw.get("name"), 100, false);
        String sector = field(raw.get("sector_name"), 100, true);
        // 공급 서버가 수집하는 KOSPI를 기존 DB의 키움 시장 코드로 유지한다.
        if (!"KOSPI".equals(raw.get("market"))) throw invalid();
        return Map.of("return_code", 0, "code", code, "name", name, "marketCode", "0", "upName", sector);
    }

    private String field(Object raw, int maxLength, boolean allowEmpty) {
        if (!(raw instanceof String value)) throw invalid();
        String text = value.trim();
        if ((!allowEmpty && text.isEmpty()) || text.length() > maxLength) throw invalid();
        return text;
    }

    private IllegalStateException invalid() {
        return new IllegalStateException("시세 서버 종목정보 응답 형식이 올바르지 않습니다.");
    }
}
