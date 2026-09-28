package com.stock_spoon.river_be.auth.cookie;

import java.time.Duration;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import com.stock_spoon.river_be.auth.token.LoginTokens;
import com.stock_spoon.river_be.config.JwtProperties;
import com.stock_spoon.river_be.config.KakaoProperties;

@Component
public class AuthCookieService {
    public static final String ACCESS_COOKIE = "access_token";
    public static final String REFRESH_COOKIE = "refresh_token";

    private final JwtProperties jwtProperties;
    private final KakaoProperties kakaoProperties;

    public AuthCookieService(JwtProperties jwtProperties, KakaoProperties kakaoProperties) {
        this.jwtProperties = jwtProperties;
        this.kakaoProperties = kakaoProperties;
    }

    public void write(HttpServletResponse response, LoginTokens tokens) {
        add(response, ACCESS_COOKIE, tokens.accessToken(), "/", jwtProperties.accessExpiration());
        add(response, REFRESH_COOKIE, tokens.refreshToken(),
                "/api/v1/auth", jwtProperties.refreshExpiration());
    }

    public void clear(HttpServletResponse response) {
        add(response, ACCESS_COOKIE, "", "/", Duration.ZERO);
        add(response, REFRESH_COOKIE, "", "/api/v1/auth", Duration.ZERO);
    }

    private void add(HttpServletResponse response, String name, String value,
            String path, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(kakaoProperties.secureCookie())
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
