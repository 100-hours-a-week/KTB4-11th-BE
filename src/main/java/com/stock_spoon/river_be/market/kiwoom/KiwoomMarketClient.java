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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KiwoomMarketClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
            new ParameterizedTypeReference<>() {};
    private final RestClient client;
    private final KiwoomTokenProvider tokens;
    private long nextQueryAt;

    public KiwoomMarketClient(RestClient client, KiwoomTokenProvider tokens) {
        this.client = client;
        this.tokens = tokens;
    }

    public KospiIndex kospi() {
        String token = tokens.accessToken();
        Map<String, Object> response;
        try {
            response = query("ka20001", "/api/dostk/sect", Map.of("mrkt_tp", "0", "inds_cd", "001"), token);
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
            log.warn("event=kiwoom_response_invalid apiId=ka20001");
            throw new IllegalStateException("키움 코스피 지수 응답 형식이 올바르지 않습니다.");
        }
    }

    /** ka10100: 주문 대상의 상장 시장과 종목 상태를 조회한다. */
    public StockInfo stockInfo(String stockCode) {
        Map<String, Object> response = stockInfoResponse(stockCode);
        if (!stockCode.equals(response.get("code"))
                || !(response.get("marketCode") instanceof String marketCode)
                || marketCode.isBlank()) {
            log.warn("event=kiwoom_response_invalid apiId=ka10100 stockCode={}", stockCode);
            throw new IllegalStateException("키움 종목정보 응답 형식이 올바르지 않습니다.");
        }
        String state = response.get("state") instanceof String value ? value : "";
        return new StockInfo(stockCode, marketCode, state,
                String.valueOf(response.getOrDefault("orderWarning", "")));
    }

    /** ka10001 주식기본정보요청의 현재가. 가격의 +/- 방향 표기는 제거한다. */
    public long currentPrice(String stockCode) {
        if (stockCode == null || !stockCode.matches("[0-9A-Z]{6}")) {
            throw new IllegalArgumentException("종목코드를 확인하세요.");
        }
        Map<String, Object> response;
        String token = tokens.accessToken();
        try {
            response = query("ka10001", "/api/dostk/stkinfo", Map.of("stk_cd", stockCode), token);
        } catch (RestClientException error) {
            throw new IllegalStateException("키움 현재가 조회에 실패했습니다.");
        }
        if (response == null || !"0".equals(String.valueOf(response.get("return_code")))) {
            throw new IllegalStateException("키움 현재가 조회가 거부되었습니다.");
        }
        try {
            if (!stockCode.equals(response.get("stk_cd"))) throw new IllegalArgumentException();
            long price = number(response.get("cur_prc")).abs().longValueExact();
            if (price <= 0) throw new IllegalArgumentException();
            return price;
        } catch (RuntimeException error) {
            log.warn("event=kiwoom_response_invalid apiId=ka10001 stockCode={}", stockCode);
            throw new IllegalStateException("키움 현재가 응답 형식이 올바르지 않습니다.");
        }
    }

    /** 업종코드 사전을 추측해 연결하지 않고, 종목정보의 업종명을 그대로 읽는다. */
    public StockDetails stockDetails(String stockCode) {
        var response = stockInfoResponse(stockCode);
        if (!stockCode.equals(response.get("code")) || !(response.get("name") instanceof String name)
                || name.isBlank()) {
            log.warn("event=kiwoom_response_invalid apiId=ka10100 stockCode={} field=name", stockCode);
            throw new IllegalStateException("키움 종목명 응답 형식이 올바르지 않습니다.");
        }
        Object sector = response.get("upName");
        if (sector != null && !(sector instanceof String)) {
            log.warn("event=kiwoom_response_invalid apiId=ka10100 stockCode={} field=sector", stockCode);
            throw new IllegalStateException("키움 업종명 응답 형식이 올바르지 않습니다.");
        }
        return new StockDetails(stockCode, name, sector == null ? "" : ((String) sector).trim());
    }

    // 주문 검증과 보유 조회가 같은 ka10100 호출을 재사용하되 각자의 필수 필드만 검증한다.
    private Map<String, Object> stockInfoResponse(String stockCode) {
        if (stockCode == null || !stockCode.matches("[0-9A-Z]{6}")) throw new IllegalArgumentException("종목코드를 확인하세요.");
        // 토큰 갱신 대기가 끝난 뒤 간격을 적용해야 대기 요청이 한꺼번에 출발하지 않는다.
        String token = tokens.accessToken();
        Map<String, Object> response;
        try {
            response = query("ka10100", "/api/dostk/stkinfo", Map.of("stk_cd", stockCode), token);
        } catch (RestClientException error) {
            throw new IllegalStateException("키움 종목정보 조회에 실패했습니다.");
        }
        if (response == null || !"0".equals(String.valueOf(response.get("return_code")))) {
            throw new IllegalStateException("키움 종목정보 조회가 거부되었습니다.");
        }
        log.debug("키움 종목정보 수신 apiId=ka10100 stockCode={}", stockCode);
        return response;
    }

    static String safeReturnCode(Map<String, Object> response) {
        String code = response == null ? "missing" : String.valueOf(response.get("return_code"));
        return code.matches("-?[0-9]{1,10}") ? code : "invalid";
    }

    static String safeDetailCode(Map<String, Object> response) {
        if (response == null) return "missing";
        var match = java.util.regex.Pattern.compile("\\[(\\d{3,5}):")
                .matcher(String.valueOf(response.get("return_msg")));
        return match.find() ? match.group(1) : "missing";
    }

    // ponytail: 단일 서버 직렬 조회. 처리량이 필요하면 검증된 배치 조회와 분산 호출 제한으로 교체한다.
    // 응답 완료 후 간격을 둬 토큰 갱신·요청 초기화에 지연된 요청도 몰아서 출발하지 않게 한다.
    private Map<String, Object> query(String apiId, String path, Map<String, String> body, String token) {
        var response = queryOnce(apiId, path, body, token);
        if ("8005".equals(safeReturnCode(response)) || "8005".equals(safeDetailCode(response))) {
            // 다른 요청이 이미 갱신한 토큰은 invalidate가 보존한다. 두 번째 응답은 재시도하지 않는다.
            tokens.invalidate(token);
            String refreshedToken = tokens.accessToken();
            log.info("event=kiwoom_query_auth_retry apiId={} detailCode=8005", apiId);
            response = queryOnce(apiId, path, body, refreshedToken);
        }
        return response;
    }

    private synchronized Map<String, Object> queryOnce(String apiId, String path, Map<String, String> body, String token) {
        long remaining = nextQueryAt - System.nanoTime();
        if (remaining > 0) {
            try {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("키움 조회 대기가 중단되었습니다.");
            }
        }
        long started = System.nanoTime();
        log.debug("event=kiwoom_query_started apiId={}", apiId);
        try {
            var response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                    .header("api-id", apiId).headers(headers -> headers.setBearerAuth(token))
                    .body(body).retrieve().body(JSON);
            String returnCode = safeReturnCode(response);
            if (!"0".equals(returnCode)) {
                log.warn("event=kiwoom_query_rejected apiId={} returnCode={} detailCode={} elapsedMs={}",
                        apiId, returnCode, safeDetailCode(response), (System.nanoTime() - started) / 1_000_000);
            } else {
                log.debug("event=kiwoom_query_completed apiId={} elapsedMs={}",
                        apiId, (System.nanoTime() - started) / 1_000_000);
            }
            return response;
        } catch (RestClientException error) {
            int status = error instanceof org.springframework.web.client.RestClientResponseException http
                    ? http.getStatusCode().value() : 0;
            log.error("event=kiwoom_query_failed apiId={} httpStatus={} causeType={} elapsedMs={}",
                    apiId, status, error.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
            throw error;
        } finally {
            nextQueryAt = System.nanoTime() + 220_000_000L;
        }
    }

    public record StockDetails(String stockCode, String stockName, String sector) {}

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
