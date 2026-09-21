package com.stock_spoon.river_be.auth.controller;

import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import com.stock_spoon.river_be.auth.dto.KakaoLoginRequest;
import com.stock_spoon.river_be.auth.dto.KakaoLoginResponse;
import com.stock_spoon.river_be.auth.service.KakaoAuthService;

// FE 로그인 요청을 받는 입구
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final KakaoAuthService service;

    public AuthController(KakaoAuthService service) {
        this.service = service;
    }

    // OAuth state와 별개로, BE 로그인 요청의 CSRF 방어에 사용합니다.
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrfToken) {
        return Map.of("token", csrfToken.getToken(), "header_name", csrfToken.getHeaderName());
    }

    // 이건 언제 호출되는거지?
    @PostMapping("/login")
    public KakaoLoginResponse login(@Valid @RequestBody KakaoLoginRequest request) {
        service.verify(request.authorizationCode());
        return KakaoLoginResponse.verified();
    }
}
