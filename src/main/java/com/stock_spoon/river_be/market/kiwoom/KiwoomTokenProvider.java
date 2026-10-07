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

/**
 * 키움 API 호출에 사용할 액세스 토큰을 발급받고 BE 메모리에 보관하는 클래스.
 * 호출 흐름: 키움 REST/웹소켓 사용 → accessToken() → 캐시 재사용 또는 새 토큰 발급.
 * 토큰과 APP KEY/SECRET은 사용자 응답이나 로그에 기록하지 않는다.
 * 발급 시점은 최초 사용 시점이며, 이 클래스 자체에는 주기적으로 갱신하는 스케줄러가 없다.
 */
public class KiwoomTokenProvider {
    // 토큰 값 대신 발급 성공·실패·폐기 여부와 소요 시간만 기록하는 로거.
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KiwoomTokenProvider.class);
    // 키움 JSON 응답을 Map으로 읽기 위한 타입 정보. 키는 필드명, 값은 문자열·숫자 등이다.
    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
            new ParameterizedTypeReference<>() {};
    // expires_dt 예: 20261007210000. 연·월·일·시·분·초 순서로 읽고 잘못된 날짜는 거절한다.
    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);
    // 키움 만료 문자열에는 시간대가 없으므로 한국 시간으로 해석한다.
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    // KiwoomConfig가 키움 서버 주소와 통신 설정을 넣어 전달하는 HTTP 클라이언트.
    private final RestClient client;
    // 키움에서 발급한 앱 식별 키. 스톡스푼 로그인 JWT와는 별개의 인증 정보다.
    private final String appKey;
    // APP KEY와 함께 토큰 발급 요청에 사용하는 비밀 키.
    private final String appSecret;
    // 현재 시각을 제공한다. 운영에서는 실제 시계, 테스트에서는 고정 시계를 전달할 수 있다.
    private final Clock clock;
    // 이 BE 인스턴스 메모리에 저장된 액세스 토큰. 최초 생성/재시작 시 null이다.
    private String token;
    // 토큰의 절대 만료 시각. EPOCH(1970년)은 아직 유효한 토큰이 없다는 초기값이다.
    private Instant expiresAt = Instant.EPOCH;

    /** 의존성을 보관한다. 생성자에서 실제 토큰 발급 요청을 보내지는 않는다. */
    public KiwoomTokenProvider(RestClient client, String appKey, String appSecret, Clock clock) {
        this.client = client;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.clock = clock;
    }

    // ponytail: 단일 서버 메모리 캐시. 여러 서버 운영 시 공유 저장소와 분산 잠금을 검토한다.
    /**
     * API 호출에 사용할 토큰을 반환한다. 유효한 캐시가 없으면 동기적으로 발급받는다.
     * synchronized: 같은 인스턴스의 발급·폐기가 동시에 실행되지 않도록 잠근다.
     * 여러 EC2/BE 인스턴스 사이의 동시 발급까지 막는 잠금은 아니다.
     * 발급 실패는 호출한 서비스로 전달되어 해당 API의 실패 응답으로 이어질 수 있다.
     */
    public synchronized String accessToken() {
        // 만료 60초 전까지 재사용한다. 정확히 60초 남은 시점부터는 새 발급이 필요하다.
        if (token != null && clock.instant().isBefore(expiresAt.minusSeconds(60))) {
            // 이 경로에서는 키움 서버에 토큰 발급 요청을 하지 않는다.
            return token;
        }
        // 앱 인증 정보가 없으면 외부 요청 전에 설정 오류로 종료한다.
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            log.error("event=kiwoom_token_not_configured");
            throw new IllegalStateException("KIWOOM_APP_KEY와 KIWOOM_APP_SECRET 설정이 필요합니다.");
        }

        // 경과 시간 측정용 시계. 토큰 만료 판단에는 위의 clock을 사용한다.
        long started = System.nanoTime();
        log.debug("event=kiwoom_token_refresh_started");
        // 발급 응답의 return_code, token, token_type, expires_dt 등을 담는다.
        Map<String, Object> response;
        try {
            // 키움 토큰 발급 API에 POST 요청을 구성한다.
            response = client.post().uri("/oauth2/token")
                    // 요청 Body를 JSON 형식으로 전달한다.
                    .contentType(MediaType.APPLICATION_JSON)
                    // 앱 키·시크릿으로 인증하는 client_credentials 방식이다.
                    .body(Map.of("grant_type", "client_credentials",
                            "appkey", appKey, "secretkey", appSecret))
                    // 실제 요청을 보내고 응답 JSON을 위에서 정의한 Map 타입으로 읽는다.
                    .retrieve().body(JSON);
        } catch (RestClientException error) {
            // HTTP 오류이면 상태 코드를, 연결 실패 등 HTTP 응답이 없으면 0을 기록한다.
            int status = error instanceof org.springframework.web.client.RestClientResponseException http
                    ? http.getStatusCode().value() : 0;
            log.error("event=kiwoom_token_failed httpStatus={} causeType={} elapsedMs={}",
                    status, error.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
            // 공급자 예외에는 응답 본문이 포함될 수 있으므로 원문을 전달하지 않는다.
            // 예외의 가장 안쪽 원인까지 따라간다. 메시지 원문 대신 예외 종류만 사용한다.
            Throwable root = error;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            throw new IllegalStateException("키움 토큰 발급 통신에 실패했습니다 ("
                    + error.getClass().getSimpleName() + "/" + root.getClass().getSimpleName()
                    + "). 네트워크와 인증 설정을 확인하세요.");
        }
        // 키움은 HTTP 성공 응답에서도 return_code로 인증/업무 실패를 알릴 수 있다.
        if (response != null && !"0".equals(String.valueOf(response.get("return_code")))) {
            log.warn("event=kiwoom_token_rejected returnCode={} detailCode={}",
                    KiwoomMarketClient.safeReturnCode(response), KiwoomMarketClient.safeDetailCode(response));
            // return_msg의 [8050:...] 같은 표기에서 숫자 상세 오류 코드만 추출한다.
            var detail = java.util.regex.Pattern.compile("\\[(\\d{3,5}):")
                    .matcher(String.valueOf(response.get("return_msg")));
            // 8050이면 설정 점검에 도움이 되는 전용 오류 메시지를 사용한다.
            if (detail.find() && "8050".equals(detail.group(1))) {
                throw new IllegalStateException(
                        "키움 단말기 인증이 거부되었습니다(8050). 허용 IP와 계좌 APP KEY 등록을 확인하세요.");
            }
        }
        // 성공 코드·비어 있지 않은 토큰·Bearer 타입·만료 문자열이 모두 있어야 저장한다.
        // instanceof의 newToken/type/expiry 변수는 검사 통과 후 아래에서 사용한다.
        if (response == null || !"0".equals(String.valueOf(response.get("return_code")))
                || !(response.get("token") instanceof String newToken) || newToken.isBlank()
                || !(response.get("token_type") instanceof String type)
                || !"bearer".equalsIgnoreCase(type)
                || !(response.get("expires_dt") instanceof String expiry)) {
            throw invalidResponse();
        }
        // 문자열로 받은 만료 시각을 비교 가능한 절대 시각으로 변환한다.
        Instant newExpiry;
        try {
            // 한국 지역 시각으로 읽은 뒤 Instant로 변환한다. BE 운영체제 시간대에 의존하지 않는다.
            newExpiry = LocalDateTime.parse(expiry, EXPIRY_FORMAT).atZone(KOREA).toInstant();
        } catch (java.time.DateTimeException error) {
            // 날짜 형식이 잘못되면 새 토큰을 캐시에 넣지 않는다.
            throw invalidResponse();
        }
        // 새 토큰도 최소 60초보다 긴 유효 시간이 있어야 허용한다. 이미 만료된 응답은 거절한다.
        if (!newExpiry.isAfter(clock.instant().plusSeconds(60))) {
            throw invalidResponse();
        }
        // 모든 검증이 끝난 뒤 토큰과 만료 시각을 함께 교체한다. 앞에서 실패하면 기존 값은 유지된다.
        token = newToken;
        expiresAt = newExpiry;
        log.info("event=kiwoom_token_refreshed elapsedMs={}", (System.nanoTime() - started) / 1_000_000);
        // 호출자는 이 문자열을 Authorization: Bearer <토큰> 또는 웹소켓 LOGIN에 사용한다.
        return token;
    }

    // REST 8005 또는 WebSocket LOGIN에서 거부된 토큰만 폐기한다. 이미 갱신된 토큰은 유지한다.
    /**
     * 키움이 거절한 요청에 사용된 토큰을 캐시에서 제거한다.
     * REST 8005 처리에서는 이 메서드 다음 accessToken()을 호출하여 토큰을 확보한다.
     * 이 메서드 자체는 새 발급 요청을 보내지 않는다.
     */
    synchronized void invalidate(String rejectedToken) {
        // 오래된 요청의 실패가 다른 요청에서 이미 갱신한 새 토큰을 삭제하지 않도록 비교한다.
        if (token != null && token.equals(rejectedToken)) {
            log.warn("event=kiwoom_token_invalidated");
            // 캐시를 비워 다음 accessToken() 호출이 재사용 대신 발급 경로로 들어가게 한다.
            token = null;
            // 토큰 폐기에 맞춰 만료 시각도 초기화한다.
            expiresAt = Instant.EPOCH;
        }
    }

    /** 잘못된 발급 응답을 처리할 때 공통 로그와 예외를 만든다. 실제 throw는 호출부가 수행한다. */
    private IllegalStateException invalidResponse() {
        log.warn("event=kiwoom_token_response_invalid");
        return new IllegalStateException(
                "키움 토큰 발급 응답이 유효하지 않습니다. 앱 키, 시크릿, 허용 IP 및 서버 시간을 확인하세요.");
    }
}
