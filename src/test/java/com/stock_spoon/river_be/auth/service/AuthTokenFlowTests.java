package com.stock_spoon.river_be.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import com.stock_spoon.river_be.auth.client.KakaoClient;
import com.stock_spoon.river_be.auth.client.KakaoUserInfo;
import com.stock_spoon.river_be.auth.exception.AuthException;
import com.stock_spoon.river_be.auth.repository.RefreshTokenRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "kakao.client-id=test-app",
        "kakao.redirect-uri=http://localhost:3000/callback"
})
@Transactional
class AuthTokenFlowTests {
    @Autowired AuthService service;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired @Qualifier("accessJwtDecoder") JwtDecoder accessDecoder;
    @MockitoBean KakaoClient kakao;

    @Test
    void loginIssuesAccessAndRefreshTokensForStockSpoonUser() {
        when(kakao.verifyUser("code"))
                .thenReturn(new KakaoUserInfo(123L, "닉네임", null));

        var tokens = service.login("code");
        var access = accessDecoder.decode(tokens.accessToken());

        assertThat(Long.parseLong(access.getSubject())).isPositive();
        assertThat(access.getClaimAsString("type")).isEqualTo("access");
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(refreshTokens.count()).isEqualTo(1);
    }

    @Test
    void reissueRotatesRefreshTokenAndRejectsItsReuse() {
        when(kakao.verifyUser("code"))
                .thenReturn(new KakaoUserInfo(456L, "닉네임", null));
        var first = service.login("code");

        var second = service.reissue(first.refreshToken());

        assertThat(second.accessToken()).isNotEqualTo(first.accessToken());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(refreshTokens.count()).isEqualTo(1);
        assertThatThrownBy(() -> service.reissue(first.refreshToken()))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logoutRevokesCurrentRefreshToken() {
        when(kakao.verifyUser("code"))
                .thenReturn(new KakaoUserInfo(789L, "닉네임", null));
        var tokens = service.login("code");

        service.logout(tokens.refreshToken());

        assertThat(refreshTokens.count()).isZero();
        assertThatThrownBy(() -> service.reissue(tokens.refreshToken()))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_REFRESH_TOKEN"));
    }
}
