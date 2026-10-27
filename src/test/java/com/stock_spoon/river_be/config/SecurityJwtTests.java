package com.stock_spoon.river_be.config;

import java.time.Instant;
import java.util.Map;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import com.stock_spoon.river_be.auth.client.KakaoClient;
import com.stock_spoon.river_be.auth.client.KakaoUserInfo;
import com.stock_spoon.river_be.auth.service.AuthService;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "kakao.client-id=test-app",
        "kakao.redirect-uri=http://localhost:3000/callback",
        "kakao.frontend-origin=https://app.example.com, http://localhost:3001, "
})
@Import(SecurityJwtTests.ProtectedController.class)
@Transactional
class SecurityJwtTests {
    @Autowired WebApplicationContext context;
    @Autowired AuthService authService;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JwtProperties jwtProperties;
    // [회원 탈퇴 테스트 수정] 일반 토큰에 DB 회원 존재 검사가 추가되어 실제 회원 fixture가 필요하다.
    @Autowired com.stock_spoon.river_be.user.repository.UserRepository users;
    @MockitoBean KakaoClient kakao;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void corsAllowsConfiguredOriginsAndRejectsOthers() throws Exception {
        for (String origin : new String[] {"https://app.example.com", "http://localhost:3001"}) {
            mvc.perform(options("/api/v1/auth/login")
                            .header("Origin", origin)
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "Content-Type,X-XSRF-TOKEN,ngrok-skip-browser-warning"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                    .andExpect(header().string("Access-Control-Allow-Headers",
                            org.hamcrest.Matchers.containsString("ngrok-skip-browser-warning")));
            mvc.perform(get("/api/v1/auth/csrf").header("Origin", origin))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
        mvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "https://untrusted.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void accessTokenCookieAuthenticatesProtectedRequest() throws Exception {
        when(kakao.verifyUser("code"))
                .thenReturn(new KakaoUserInfo(1000L, "닉네임", null));
        var tokens = authService.login("code");

        mvc.perform(get("/test/protected")
                        .cookie(new Cookie("access_token", tokens.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").isNotEmpty());
    }

    @Test
    void refreshTokenCannotAuthenticateProtectedRequest() throws Exception {
        when(kakao.verifyUser("code"))
                .thenReturn(new KakaoUserInfo(1001L, "닉네임", null));
        var tokens = authService.login("code");

        mvc.perform(get("/test/protected")
                        .cookie(new Cookie("access_token", tokens.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedAccessTokenIsRejected() throws Exception {
        when(kakao.verifyUser("code"))
                .thenReturn(new KakaoUserInfo(1002L, "닉네임", null));
        var tokens = authService.login("code");

        mvc.perform(get("/test/protected")
                        .cookie(new Cookie("access_token", tokens.accessToken() + "changed")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredAccessTokenIsRejected() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject("1")
                .issuedAt(now.minusSeconds(120))
                .expiresAt(now.minusSeconds(60))
                .claim("type", "access")
                .build();
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();

        mvc.perform(get("/test/protected")
                        .cookie(new Cookie("access_token", expired)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAiServerCookieCanReachTheUserSnapshotEndpoint() throws Exception {
        String path = "/api/v1/users/ai-server";
        // 없는 회원의 401 검사 대신, 존재하는 일반 회원의 AI 전용 API 접근이 403인지 검사한다.
        String userId = users.save(new com.stock_spoon.river_be.user.entity.User("일반 사용자")).getId().toString();
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer " + aiToken("ai-server", "access", "AI")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).cookie(new Cookie("access_token", aiToken(userId, "access", null))))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).cookie(new Cookie("access_token", aiToken(userId, "access", "AI"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).cookie(new Cookie("access_token", aiToken("ai-server", "refresh", "AI"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).cookie(new Cookie("access_token", aiToken("ai-server", "access", "AI"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").isArray());
    }

    @Test
    void aiServerTokenCannotReachOtherProtectedEndpoints() throws Exception {
        mvc.perform(get("/test/protected")
                        .cookie(new Cookie("access_token", aiToken("ai-server", "access", "AI"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/test/protected")).andExpect(status().isUnauthorized());
    }

    private String aiToken(String subject, String type, String actor) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(jwtProperties.issuer()).subject(subject)
                .issuedAt(now).expiresAt(now.plusSeconds(900)).claim("type", type);
        if (actor != null) {
            claims.claim("actor", actor);
        }
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims.build())).getTokenValue();
    }

    @RestController
    static class ProtectedController {
        @GetMapping("/test/protected")
        Map<String, String> protectedEndpoint(Authentication authentication) {
            return Map.of("user_id", authentication.getName());
        }
    }
}
