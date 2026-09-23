package com.stock_spoon.river_be.auth.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.stock_spoon.river_be.auth.exception.AuthException;
import com.stock_spoon.river_be.auth.token.LoginTokens;

@Service
public class AuthService {
    private final KakaoAuthService kakaoAuthService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(KakaoAuthService kakaoAuthService, RefreshTokenService refreshTokenService) {
        this.kakaoAuthService = kakaoAuthService;
        this.refreshTokenService = refreshTokenService;
    }

    public LoginTokens login(String authorizationCode) {
        var user = kakaoAuthService.verify(authorizationCode);
        return refreshTokenService.create(user);
    }

    public LoginTokens reissue(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw invalidRefreshToken();
        }
        return refreshTokenService.rotate(refreshToken);
    }

    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    private AuthException invalidRefreshToken() {
        return new AuthException(HttpStatus.UNAUTHORIZED,
                "INVALID_REFRESH_TOKEN", "Refresh Token이 유효하지 않습니다.");
    }
}
