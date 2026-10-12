package com.stock_spoon.river_be.market.kiwoom;

import java.util.Map;

public interface KiwoomRestTransport {
    Map<String, Object> query(String apiId, String path, Map<String, String> body);

    default Map<String, Object> metadata(String stockCode) {
        return query("ka10100", "/api/dostk/stkinfo", Map.of("stk_cd", stockCode));
    }
}
