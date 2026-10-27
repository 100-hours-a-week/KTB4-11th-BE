package com.stock_spoon.river_be.auth.client;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.stereotype.Component;
import com.stock_spoon.river_be.auth.exception.AuthException;
import com.stock_spoon.river_be.config.KakaoProperties;

// 카카오 서버에 토큰과 사용자 정보를 요청
@Component
public class KakaoClient {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KakaoClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
            new ParameterizedTypeReference<>() {};
    private final RestClient client;
    private final KakaoProperties properties;

    public KakaoClient(RestClient kakaoRestClient, KakaoProperties properties) {
        this.client = kakaoRestClient;
        this.properties = properties;
    }

    // [회원 탈퇴 추가] 내부 회원 ID가 아닌 카카오 식별자로 앱 연결을 해제한다. 기존 call()의 통신 오류 변환을 재사용한다.
    public void unlink(long providerUserId) {
        // Admin 키가 없으면 삭제 전에 503을 반환한다. 로그인용 Client Secret과는 별개의 서버 전용 키다.
        if (properties.adminKey() == null || properties.adminKey().isBlank()) {
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE,
                    "KAKAO_UNLINK_NOT_CONFIGURED", "카카오 연결 해제 설정이 필요합니다.");
        }
        // 카카오 Admin 키 인증 규격에 따라 대상 타입과 카카오 회원번호를 폼으로 전달한다.
        var form = new LinkedMultiValueMap<String, String>();
        form.add("target_id_type", "user_id");
        form.add("target_id", Long.toString(providerUserId));
        Map<String, Object> result = call(() -> {
            try {
                return client.post().uri("https://kapi.kakao.com/v1/user/unlink")
                        .header("Authorization", "KakaoAK " + properties.adminKey())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                        .retrieve().body(JSON);
            } catch (RestClientResponseException error) {
                // 연결 해제 성공 뒤 DB 실패/응답 유실로 재요청한 경우도 허용한다.
                if (error.getStatusCode().value() == 400) {
                    Map<?, ?> body = error.getResponseBodyAs(Map.class);
                    if (body != null && body.get("code") instanceof Number code && code.intValue() == -101)
                        return Map.of("id", providerUserId);
                }
                throw error;
            }
        }, false);
        // 다른 사용자의 응답이나 비어 있는 응답을 삭제 성공으로 처리하지 않는다.
        if (result == null || !(result.get("id") instanceof Number id) || id.longValue() != providerUserId)
            throw providerError();
    }

    public KakaoUserInfo verifyUser(String code) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.clientId());
        // 리다이렉트 URI를 한경변수로 application.yaml에 넣으면 됨
        form.add("redirect_uri", properties.redirectUri());
        form.add("code", code);
        if (properties.clientSecret() != null && !properties.clientSecret().isBlank()) {
            form.add("client_secret", properties.clientSecret());
        }
        // 인가코드로 카카오에 토큰 요청 POST https://kauth.kakao.com/oauth/token
        Map<String, Object> token = call(() -> client.post()
                .uri("https://kauth.kakao.com/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(JSON), true);
        if (token == null || !(token.get("access_token") instanceof String accessToken)
                || accessToken.isBlank()) {
            throw providerError();
        }
        // 그 토큰으로 사용자 정보 조회 GET https://kapi.kakao.com/v2/user/me
        Map<String, Object> user = call(() -> client.get()
                .uri("https://kapi.kakao.com/v2/user/me")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve().body(JSON), false);
        if (user == null || !(user.get("id") instanceof Number id)
                || id.longValue() <= 0 || id.doubleValue() != id.longValue()) {
            throw providerError();
        }
        String nickname = null;
        String profileImageUrl = null;
        if (user.get("kakao_account") instanceof Map<?, ?> account
                && account.get("profile") instanceof Map<?, ?> profile) {
            if (profile.get("nickname") instanceof String value) {
                nickname = value;
            }
            if (profile.get("profile_image_url") instanceof String value && !value.isBlank()) {
                profileImageUrl = value;
            }
        }
        return new KakaoUserInfo(id.longValue(), nickname, profileImageUrl);
    }

    private <T> T call(Supplier<T> request, boolean tokenExchange) {
        long started = System.nanoTime();
        String stage = tokenExchange ? "token_exchange" : "user_info";
        try {
            T result = request.get();
            log.debug("event=kakao_call_completed stage={} elapsedMs={}", stage, (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (RestClientResponseException error) {
            log.warn("event=kakao_call_failed stage={} httpStatus={} causeType={} elapsedMs={}",
                    stage, error.getStatusCode().value(), error.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
            if (tokenExchange) {
                String oauthError = oauthError(error);
                if ("invalid_grant".equals(oauthError)) {
                    throw new AuthException(HttpStatus.BAD_REQUEST,
                            "INVALID_AUTHORIZATION_CODE", "인가코드가 만료되었거나 유효하지 않습니다.");
                }
                if ("invalid_client".equals(oauthError) || "unauthorized_client".equals(oauthError)) {
                    throw new AuthException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "OAUTH_CONFIGURATION_ERROR", "카카오 인증 설정을 확인해야 합니다.");
                }
            }
            throw providerError();
        } catch (ResourceAccessException error) {
            log.warn("event=kakao_call_failed stage={} causeType={} elapsedMs={}",
                    stage, error.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
            Throwable cause = error;
            while (cause != null) {
                if (cause instanceof java.net.SocketTimeoutException
                        || cause instanceof java.net.http.HttpTimeoutException) {
                    throw new AuthException(HttpStatus.GATEWAY_TIMEOUT,
                            "OAUTH_PROVIDER_TIMEOUT", "카카오 인증 서버 응답 시간이 초과되었습니다.");
                }
                cause = cause.getCause();
            }
            throw providerError();
        } catch (RestClientException error) {
            log.warn("event=kakao_call_failed stage={} causeType={} elapsedMs={}",
                    stage, error.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
            throw providerError();
        }
    }

    private String oauthError(RestClientResponseException error) {
        try {
            Map<?, ?> body = error.getResponseBodyAs(Map.class);
            return body == null ? "" : String.valueOf(body.get("error"));
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private AuthException providerError() {
        return new AuthException(HttpStatus.BAD_GATEWAY,
                "OAUTH_PROVIDER_ERROR", "카카오 인증 서버 응답을 처리할 수 없습니다.");
    }
}
