package com.stock_spoon.river_be.auth.client;

import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.stock_spoon.river_be.auth.exception.AuthException;
import com.stock_spoon.river_be.config.KakaoProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KakaoClientTests {
    private MockRestServiceServer server;
    private KakaoClient client;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoClient(builder.build(), new KakaoProperties(
                "test-app", "test-secret", "http://localhost:3000/callback", "", false));
    }

    @Test
    void exchangesFormCodeAndUsesTokenOnlyForKakaoUserRequest() {
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("code=test-code")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("client_secret=test-secret")))
                .andRespond(withSuccess("{\"access_token\":\"kakao-secret\",\"refresh_token\":\"unused\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer kakao-secret"))
                .andRespond(withSuccess("""
                        {"id":123456789,"kakao_account":{"profile":{"nickname":"카카오닉네임"}}}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.verifyUser("test-code"))
                .isEqualTo(new KakaoUserInfo(123456789L, "카카오닉네임"));
        server.verify();
    }

    @Test
    void returnsMissingNicknameForServicePolicyValidation() {
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andRespond(withSuccess("{\"access_token\":\"token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andRespond(withSuccess("{\"id\":123}", MediaType.APPLICATION_JSON));

        assertThat(client.verifyUser("code")).isEqualTo(new KakaoUserInfo(123L, null));
    }

    @Test
    void mapsExpiredCodeWithoutExposingProviderBody() {
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andRespond(withBadRequest().body("{\"error\":\"invalid_grant\",\"error_description\":\"secret\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.verifyUser("expired"))
                .isInstanceOfSatisfying(AuthException.class, error -> {
                    assertThat(error.code()).isEqualTo("INVALID_AUTHORIZATION_CODE");
                    assertThat(error.getMessage()).doesNotContain("secret");
                });
        server.verify();
    }

    @Test
    void distinguishesClientConfigurationError() {
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andRespond(withBadRequest().body("{\"error\":\"invalid_client\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.verifyUser("code"))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.code()).isEqualTo("OAUTH_CONFIGURATION_ERROR"));
    }

    @Test
    void rejectsMissingAccessToken() {
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.verifyUser("code"))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void rejectsMissingUserId() {
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andRespond(withSuccess("{\"access_token\":\"token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.verifyUser("code"))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.code()).isEqualTo("OAUTH_PROVIDER_ERROR"));
    }

    @Test
    void distinguishesTimeout() {
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andRespond(withException(new SocketTimeoutException()));
        assertThatThrownBy(() -> client.verifyUser("code"))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
    }
}
