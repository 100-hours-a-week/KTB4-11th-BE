package com.stock_spoon.river_be.market.kiwoom;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 서버 내부에서만 사용한다. 토큰과 인증 정보는 응답이나 로그에 기록하지 않는다. */
public class KiwoomTokenProvider {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KiwoomTokenProvider.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
            new ParameterizedTypeReference<>() {};
    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final RestClient client;
    private final String appKey;
    private final String appSecret;
    private final Clock clock;
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    public KiwoomTokenProvider(RestClient client, String appKey, String appSecret, Clock clock) {
        this.client = client;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.clock = clock;
    }

    // ponytail: 단일 서버 메모리 캐시. 여러 서버 운영 시 공유 저장소와 분산 잠금을 검토한다.
    public synchronized String accessToken() {
        if (token != null && clock.instant().isBefore(expiresAt.minusSeconds(60))) {
            return token;
        }
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            log.error("event=kiwoom_token_not_configured");
            throw new IllegalStateException("KIWOOM_APP_KEY와 KIWOOM_APP_SECRET 설정이 필요합니다.");
        }

        long started = System.nanoTime();
        log.debug("event=kiwoom_token_refresh_started");
        Map<String, Object> response;
        try {
            response = client.post().uri("/oauth2/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("grant_type", "client_credentials",
                            "appkey", appKey, "secretkey", appSecret))
                    .retrieve().body(JSON);
        } catch (RestClientException error) {
            int status = error instanceof org.springframework.web.client.RestClientResponseException http
                    ? http.getStatusCode().value() : 0;
            log.error("event=kiwoom_token_failed httpStatus={} causeType={} elapsedMs={}",
                    status, error.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
            // 공급자 예외에는 응답 본문이 포함될 수 있으므로 원문을 전달하지 않는다.
            Throwable root = error;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            throw new IllegalStateException("키움 토큰 발급 통신에 실패했습니다 ("
                    + error.getClass().getSimpleName() + "/" + root.getClass().getSimpleName()
                    + "). 네트워크와 인증 설정을 확인하세요.");
        }
        if (response != null && !"0".equals(String.valueOf(response.get("return_code")))) {
            log.warn("event=kiwoom_token_rejected returnCode={} detailCode={}",
                    KiwoomMarketClient.safeReturnCode(response), KiwoomMarketClient.safeDetailCode(response));
            var detail = java.util.regex.Pattern.compile("\\[(\\d{3,5}):")
                    .matcher(String.valueOf(response.get("return_msg")));
            if (detail.find() && "8050".equals(detail.group(1))) {
                throw new IllegalStateException(
                        "키움 단말기 인증이 거부되었습니다(8050). 허용 IP와 계좌 APP KEY 등록을 확인하세요.");
            }
        }
        if (response == null || !"0".equals(String.valueOf(response.get("return_code")))
                || !(response.get("token") instanceof String newToken) || newToken.isBlank()
                || !(response.get("token_type") instanceof String type)
                || !"bearer".equalsIgnoreCase(type)
                || !(response.get("expires_dt") instanceof String expiry)) {
            throw invalidResponse();
        }
        Instant newExpiry;
        try {
            newExpiry = LocalDateTime.parse(expiry, EXPIRY_FORMAT).atZone(KOREA).toInstant();
        } catch (java.time.DateTimeException error) {
            throw invalidResponse();
        }
        if (!newExpiry.isAfter(clock.instant().plusSeconds(60))) {
            throw invalidResponse();
        }
        token = newToken;
        expiresAt = newExpiry;
        log.info("event=kiwoom_token_refreshed elapsedMs={}", (System.nanoTime() - started) / 1_000_000);
        return token;
    }

    // REST 8005 또는 WebSocket LOGIN에서 거부된 토큰만 폐기한다. 이미 갱신된 토큰은 유지한다.
    synchronized void invalidate(String rejectedToken) {
        if (token != null && token.equals(rejectedToken)) {
            log.warn("event=kiwoom_token_invalidated");
            token = null;
            expiresAt = Instant.EPOCH;
        }
    }

    private IllegalStateException invalidResponse() {
        log.warn("event=kiwoom_token_response_invalid");
        return new IllegalStateException(
                "키움 토큰 발급 응답이 유효하지 않습니다. 앱 키, 시크릿, 허용 IP 및 서버 시간을 확인하세요.");
    }
}
