package com.stock_spoon.river_be.auth.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.stock_spoon.river_be.auth.exception.AuthException;
import com.stock_spoon.river_be.auth.token.LoginTokens;

@Service
public class AuthService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthService.class);
    private final KakaoAuthService kakaoAuthService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(KakaoAuthService kakaoAuthService, RefreshTokenService refreshTokenService) {
        this.kakaoAuthService = kakaoAuthService;
        this.refreshTokenService = refreshTokenService;
    }

    public LoginTokens login(String authorizationCode) {
        var user = kakaoAuthService.verify(authorizationCode);
        var tokens = refreshTokenService.create(user);
        log.info("event=auth_login_completed");
        return tokens;
    }

    public LoginTokens reissue(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw invalidRefreshToken();
        }
        var tokens = refreshTokenService.rotate(refreshToken);
        log.info("event=auth_reissue_completed");
        return tokens;
    }

    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
        log.info("event=auth_logout_completed");
    }

    private AuthException invalidRefreshToken() {
        return new AuthException(HttpStatus.UNAUTHORIZED,
                "INVALID_REFRESH_TOKEN", "Refresh Token이 유효하지 않습니다.");
    }
}
