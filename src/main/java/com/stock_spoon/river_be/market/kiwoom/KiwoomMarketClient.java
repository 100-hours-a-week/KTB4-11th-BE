package com.stock_spoon.river_be.market.kiwoom;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 키움 시세 조회. 실전 주문 API는 호출하지 않는다. */
public class KiwoomMarketClient {
    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
            new ParameterizedTypeReference<>() {};
    private final RestClient client;
    private final KiwoomTokenProvider tokens;

    public KiwoomMarketClient(RestClient client, KiwoomTokenProvider tokens) {
        this.client = client;
        this.tokens = tokens;
    }

    public KospiIndex kospi() {
        Map<String, Object> response;
        try {
            response = client.post().uri("/api/dostk/sect")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("api-id", "ka20001")
                    .headers(headers -> headers.setBearerAuth(tokens.accessToken()))
                    .body(Map.of("mrkt_tp", "0", "inds_cd", "001"))
                    .retrieve().body(JSON);
        } catch (RestClientException error) {
            throw new IllegalStateException("키움 코스피 지수 조회에 실패했습니다.");
        }
        if (response == null || !"0".equals(String.valueOf(response.get("return_code")))) {
            throw new IllegalStateException("키움 코스피 지수 조회가 거부되었습니다.");
        }
        try {
            return new KospiIndex(
                    number(response.get("cur_prc")).abs(),
                    number(response.get("pred_pre")),
                    number(response.get("flu_rt")),
                    Instant.now());
        } catch (RuntimeException error) {
            throw new IllegalStateException("키움 코스피 지수 응답 형식이 올바르지 않습니다.");
        }
    }

    /** ka10100: 주문 대상의 상장 시장과 종목 상태를 조회한다. */
    public StockInfo stockInfo(String stockCode) {
        Map<String, Object> response;
        try {
            response = client.post().uri("/api/dostk/stkinfo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("api-id", "ka10100")
                    .headers(headers -> headers.setBearerAuth(tokens.accessToken()))
                    .body(Map.of("stk_cd", stockCode))
                    .retrieve().body(JSON);
        } catch (RestClientException error) {
            throw new IllegalStateException("키움 종목정보 조회에 실패했습니다.");
        }
        if (response == null || !"0".equals(String.valueOf(response.get("return_code")))) {
            throw new IllegalStateException("키움 종목정보 조회가 거부되었습니다.");
        }
        if (!stockCode.equals(response.get("code"))
                || !(response.get("marketCode") instanceof String marketCode)
                || marketCode.isBlank()) {
            throw new IllegalStateException("키움 종목정보 응답 형식이 올바르지 않습니다.");
        }
        String state = response.get("state") instanceof String value ? value : "";
        return new StockInfo(stockCode, marketCode, state,
                String.valueOf(response.getOrDefault("orderWarning", "")));
    }

    private BigDecimal number(Object value) {
        if (!(value instanceof String) && !(value instanceof Number)) {
            throw new IllegalArgumentException();
        }
        return new BigDecimal(value.toString().trim().replace(",", ""));
    }

    public record KospiIndex(BigDecimal value, BigDecimal change,
                             BigDecimal changeRate, Instant fetchedAt) {
    }

    public record StockInfo(String stockCode, String marketCode, String state,
                            String orderWarning) {}
}
