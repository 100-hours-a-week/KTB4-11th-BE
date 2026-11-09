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
        // [회원 탈퇴 테스트 수정] 연결 해제 요청의 Admin 키 인증을 검사할 수 있도록 테스트 전용 키를 설정한다.
        client = new KakaoClient(builder.build(), new KakaoProperties(
                "test-app", "test-secret", "http://localhost:3000/callback", "", false, "test-admin"));
    }

    // [회원 탈퇴 테스트 추가] 서버 Admin 키와 카카오 회원번호가 연결 해제 요청의 헤더·폼에 올바르게 전달되는지 확인한다.
    @Test void unlinkUsesAdminKeyAndCorrectProviderId() {
        server.expect(requestTo("https://kapi.kakao.com/v1/user/unlink"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "KakaoAK test-admin"))
                .andExpect(content().string("target_id_type=user_id&target_id=123"))
                .andRespond(withSuccess("{\"id\":123}", MediaType.APPLICATION_JSON));
        client.unlink(123);
        server.verify();
    }

    // [회원 탈퇴 테스트 추가] 이미 연결이 없는 -101만 허용하고 다른 카카오 인증 오류는 실패로 남기는지 확인한다.
    @Test void unlinkAcceptsAlreadyUnlinkedButRejectsOtherProviderErrors() {
        server.expect(requestTo("https://kapi.kakao.com/v1/user/unlink"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":-101,\"msg\":\"NotRegisteredUserException\"}"));
        server.expect(requestTo("https://kapi.kakao.com/v1/user/unlink"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":-401}"));
        client.unlink(123);
        assertThatThrownBy(() -> client.unlink(123)).isInstanceOf(AuthException.class);
        server.verify();
    }

    // [회원 탈퇴 테스트 추가] 연결 해제 응답이 다른 카카오 회원번호이면 삭제 성공으로 처리하지 않는지 확인한다.
    @Test void unlinkRejectsWrongUserResponse() {
        server.expect(requestTo("https://kapi.kakao.com/v1/user/unlink"))
                .andRespond(withSuccess("{\"id\":999}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.unlink(123)).isInstanceOf(AuthException.class);
    }

    // [회원 탈퇴 테스트 추가] 카카오 타임아웃을 서비스의 504 오류로 변환하는지 확인한다.
    @Test void unlinkTimeoutIsReportedAsGatewayTimeout() {
        server.expect(requestTo("https://kapi.kakao.com/v1/user/unlink"))
                .andRespond(withException(new SocketTimeoutException("timeout")));
        assertThatThrownBy(() -> client.unlink(123)).isInstanceOfSatisfying(AuthException.class,
                error -> assertThat(error.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
    }

    // [회원 탈퇴 테스트 추가] Admin 키가 없으면 외부 요청 전에 설정 오류로 중단하는지 확인한다.
    @Test void unlinkRequiresConfiguredAdminKey() {
        var unconfigured = new KakaoClient(RestClient.create(),
                new KakaoProperties("app", "", "", "", false, ""));
        assertThatThrownBy(() -> unconfigured.unlink(123)).isInstanceOfSatisfying(AuthException.class,
                error -> assertThat(error.code()).isEqualTo("KAKAO_UNLINK_NOT_CONFIGURED"));
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
                        {"id":123456789,"kakao_account":{"profile":{"nickname":"카카오닉네임","profile_image_url":"https://example.com/profile.jpg"}}}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.verifyUser("test-code"))
                .isEqualTo(new KakaoUserInfo(
                        123456789L, "카카오닉네임", "https://example.com/profile.jpg"));
        server.verify();
    }

    @Test
    void returnsMissingNicknameForServicePolicyValidation() {
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
                .andRespond(withSuccess("{\"access_token\":\"token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
                .andRespond(withSuccess("{\"id\":123}", MediaType.APPLICATION_JSON));

        assertThat(client.verifyUser("code")).isEqualTo(new KakaoUserInfo(123L, null, null));
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
