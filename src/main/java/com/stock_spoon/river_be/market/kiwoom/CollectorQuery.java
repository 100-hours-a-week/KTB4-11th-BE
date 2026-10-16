package com.stock_spoon.river_be.market.kiwoom;

import java.util.Map;

/** 외부 주소·임의 본문·주문 TR은 내부 요청으로 받지 않는다. */
public record CollectorQuery(String apiId, String stockCode, String collectorId,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String baseDate) {
    public CollectorQuery(String apiId, String stockCode, String collectorId) {
        this(apiId, stockCode, collectorId, null);
    }

    public String path() {
        if ("ka10081".equals(apiId)) return "/api/dostk/chart";
        if ("ka20001".equals(apiId)) return "/api/dostk/sect";
        if ("ka10001".equals(apiId) || "ka10100".equals(apiId)) return "/api/dostk/stkinfo";
        throw new IllegalArgumentException("허용되지 않은 조회 TR입니다.");
    }

    public Map<String, String> body() {
        path();
        if (!"ka10081".equals(apiId) && baseDate != null) throw new IllegalArgumentException("거래일을 지정할 수 없는 조회입니다.");
        if ("ka20001".equals(apiId)) {
            if (stockCode != null) throw new IllegalArgumentException("코스피 조회에는 종목코드를 지정하지 않습니다.");
            return Map.of("mrkt_tp", "0", "inds_cd", "001");
        }
        if (stockCode == null || !stockCode.matches("[0-9A-Z]{6}")) {
            throw new IllegalArgumentException("종목코드를 확인하세요.");
        }
        if ("ka10081".equals(apiId)) {
            try {
                if (baseDate == null || !baseDate.matches("[0-9]{8}")) throw new IllegalArgumentException();
                java.time.LocalDate.parse(baseDate, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("거래일 형식을 확인하세요.");
            }
            return Map.of("stk_cd", stockCode, "base_dt", baseDate, "upd_stkpc_tp", "0");
        }
        return Map.of("stk_cd", stockCode);
    }

    static CollectorQuery from(String apiId, String path, Map<String, String> body, String collectorId) {
        var query = new CollectorQuery(apiId, body.get("stk_cd"), collectorId, body.get("base_dt"));
        if (!query.path().equals(path) || !query.body().equals(body)) {
            throw new IllegalArgumentException("허용되지 않은 조회 요청입니다.");
        }
        return query;
    }
}
