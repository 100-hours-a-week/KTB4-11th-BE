package com.stock_spoon.river_be.auth.controller;

import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import com.stock_spoon.river_be.auth.cookie.AuthCookieService;
import com.stock_spoon.river_be.auth.dto.KakaoLoginRequest;
import com.stock_spoon.river_be.auth.dto.KakaoLoginResponse;
import com.stock_spoon.river_be.auth.service.AuthService;
import com.stock_spoon.river_be.dbwrite.DbWriteReplayAcceptedResponse;
import com.stock_spoon.river_be.dbwrite.DbWriteReplayDeferredException;

// FE 로그인 요청을 받는 입구
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
    public Map<String, String> csrf(CsrfToken csrfToken) {
        return Map.of("token", csrfToken.getToken(), "header_name", csrfToken.getHeaderName());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody KakaoLoginRequest request,
            HttpServletResponse response) {
        try {
            var tokens = service.login(request.authorizationCode());
            cookies.write(response, tokens);
            return ResponseEntity.ok(KakaoLoginResponse.loginSuccess(tokens.userId()));
        } catch (DbWriteReplayDeferredException deferred) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(DbWriteReplayAcceptedResponse.queued(
                    deferred.requestId(), deferred.getMessage()));
        }
    }

    @PostMapping("/reissue")
    public ResponseEntity<?> reissue(
            @CookieValue(name = AuthCookieService.REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        try {
            cookies.write(response, service.reissue(refreshToken));
            return ResponseEntity.ok(KakaoLoginResponse.tokenReissued());
        } catch (DbWriteReplayDeferredException deferred) {
            cookies.clear(response);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(DbWriteReplayAcceptedResponse.queued(
                    deferred.requestId(), deferred.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @CookieValue(name = AuthCookieService.REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        try {
            service.logout(refreshToken);
            cookies.clear(response);
            return ResponseEntity.ok(KakaoLoginResponse.logoutSuccess());
        } catch (DbWriteReplayDeferredException deferred) {
            cookies.clear(response);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(DbWriteReplayAcceptedResponse.queued(
                    deferred.requestId(), deferred.getMessage()));
        }
    }
}
