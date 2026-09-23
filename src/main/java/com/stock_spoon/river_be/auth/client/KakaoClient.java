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
    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
            new ParameterizedTypeReference<>() {};
    private final RestClient client;
    private final KakaoProperties properties;

    public KakaoClient(RestClient kakaoRestClient, KakaoProperties properties) {
        this.client = kakaoRestClient;
        this.properties = properties;
    }

    public KakaoUserInfo verifyUser(String code) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.clientId());
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
        try {
            return request.get();
        } catch (RestClientResponseException error) {
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
