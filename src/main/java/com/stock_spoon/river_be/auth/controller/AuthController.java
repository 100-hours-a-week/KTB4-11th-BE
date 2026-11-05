package com.stock_spoon.river_be.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import com.stock_spoon.river_be.auth.cookie.AuthCookieService;
import com.stock_spoon.river_be.auth.dto.KakaoLoginRequest;
import com.stock_spoon.river_be.auth.dto.KakaoLoginResponse;
import com.stock_spoon.river_be.auth.service.AuthService;

// FE 로그인 요청을 받는 입구
@Tag(name = "인증")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;
    private final AuthCookieService cookies;

    public AuthController(AuthService service, AuthCookieService cookies) {
        this.service = service;
        this.cookies = cookies;
    }

    // OAuth state와 별개로, BE 로그인 요청의 CSRF 방어에 사용합니다.
    @GetMapping("/csrf")
    @Operation(summary = "CSRF 토큰 발급")
    public Map<String, String> csrf(@io.swagger.v3.oas.annotations.Parameter(hidden = true) CsrfToken csrfToken) {
        return Map.of("token", csrfToken.getToken(), "header_name", csrfToken.getHeaderName());
    }

    @PostMapping("/login")
    @Operation(summary = "카카오 로그인")
    public KakaoLoginResponse login(@Valid @RequestBody KakaoLoginRequest request,
            HttpServletResponse response) {
        var tokens = service.login(request.authorizationCode());
        cookies.write(response, tokens);
        return KakaoLoginResponse.loginSuccess(tokens.userId());
    }

    @PostMapping("/reissue")
    @Operation(summary = "토큰 재발급")
    public KakaoLoginResponse reissue(
            @CookieValue(name = AuthCookieService.REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        cookies.write(response, service.reissue(refreshToken));
        return KakaoLoginResponse.tokenReissued();
    }

    @PostMapping("/logout")
    @Operation(summary = "로그아웃")
    public KakaoLoginResponse logout(
            @CookieValue(name = AuthCookieService.REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        service.logout(refreshToken);
        cookies.clear(response);
        return KakaoLoginResponse.logoutSuccess();
    }
}
