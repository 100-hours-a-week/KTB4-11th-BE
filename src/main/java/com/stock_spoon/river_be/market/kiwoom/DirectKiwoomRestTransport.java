package com.stock_spoon.river_be.market.kiwoom;

import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import static com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient.safeReturnCode;
import static com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient.safeDetailCode;

/** 수집 담당 프로세스의 TR별 직접 조회. */
public final class DirectKiwoomRestTransport implements KiwoomRestTransport {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DirectKiwoomRestTransport.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};
    private final RestClient client;
    private final KiwoomTokenProvider tokens;
    private final BooleanSupplier collector;
    private final Map<String, QueryGate> queryGates = new java.util.concurrent.ConcurrentHashMap<>();
    private static final class QueryGate { final java.util.ArrayDeque<Long> starts = new java.util.ArrayDeque<>(); }

    public DirectKiwoomRestTransport(RestClient client, KiwoomTokenProvider tokens, BooleanSupplier collector) {
        this.client = client;
        this.tokens = tokens;
        this.collector = collector;
    }

    private void assertCollector() {
        if (!collector.getAsBoolean()) throw new IllegalStateException("키움 수집 담당 서버를 사용할 수 없습니다.");
    }

    // 고정 담당의 한 계좌에 대해 개별 TR의 최근 1초 호출 시작 횟수를 제한한다.
    // HTTP 응답 대기는 제한 장치를 잠그지 않으며 실패·재시도도 각각 한 건으로 센다.
    public Map<String, Object> query(String apiId, String path, Map<String, String> body) {
        assertCollector();
        String token = tokens.accessToken();
        var response = queryOnce(apiId, path, body, token);
        if ("8005".equals(safeReturnCode(response)) || "8005".equals(safeDetailCode(response))) {
            // 다른 요청이 이미 갱신한 토큰은 invalidate가 보존한다. 두 번째 응답은 재시도하지 않는다.
            assertCollector();
            tokens.invalidate(token);
            String refreshedToken = tokens.accessToken();
            log.info("event=kiwoom_query_auth_retry apiId={} detailCode=8005", apiId);
            response = queryOnce(apiId, path, body, refreshedToken);
        }
        return response;
    }

    long acquirePermit(String apiId) {
        long waitingStarted = System.nanoTime();
        var gate = queryGates.computeIfAbsent(apiId, ignored -> new QueryGate());
        synchronized (gate) {
            for (;;) {
                long now = System.nanoTime();
                long waitRemaining = java.util.concurrent.TimeUnit.SECONDS.toNanos(3) - (now - waitingStarted);
                if (waitRemaining <= 0) throw new IllegalStateException("키움 조회 대기 시간이 초과되었습니다.");
                while (!gate.starts.isEmpty() && now - gate.starts.getFirst() >= 1_000_000_000L) {
                    gate.starts.removeFirst();
                }
                if (gate.starts.size() < 5) {
                    assertCollector();
                    long started = System.nanoTime();
                    gate.starts.addLast(started);
                    return started;
                }
                long remaining = 1_000_000_000L - (now - gate.starts.getFirst());
                try {
                    java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(gate, Math.min(remaining, waitRemaining));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("키움 조회 대기가 중단되었습니다.");
                }
            }
        }
    }

    private Map<String, Object> queryOnce(String apiId, String path, Map<String, String> body, String token) {
        acquirePermit(apiId);
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
            assertCollector();
            return response;
        } catch (RestClientException error) {
            int status = error instanceof org.springframework.web.client.RestClientResponseException http
                    ? http.getStatusCode().value() : 0;
            log.error("event=kiwoom_query_failed apiId={} httpStatus={} causeType={} elapsedMs={}",
                    apiId, status, error.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
            throw error;
        }
    }

}
