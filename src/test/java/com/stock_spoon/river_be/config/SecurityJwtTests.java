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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "kakao.client-id=test-app",
        "kakao.redirect-uri=http://localhost:3000/callback"
})
@Import(SecurityJwtTests.ProtectedController.class)
@Transactional
class SecurityJwtTests {
    @Autowired WebApplicationContext context;
    @Autowired AuthService authService;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JwtProperties jwtProperties;
    @MockitoBean KakaoClient kakao;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
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
    void onlyAiServerCookieCanReachTheFutureUserSnapshotEndpoint() throws Exception {
        String path = "/api/v1/users/ai-server";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer " + aiToken("ai-server", "access", "AI")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).cookie(new Cookie("access_token", aiToken("1", "access", null))))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).cookie(new Cookie("access_token", aiToken("1", "access", "AI"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).cookie(new Cookie("access_token", aiToken("ai-server", "refresh", "AI"))))
                .andExpect(status().isUnauthorized());
        // 아직 GET 컨트롤러가 없으므로 인증·인가를 통과한 요청은 404에 도달한다.
        mvc.perform(get(path).cookie(new Cookie("access_token", aiToken("ai-server", "access", "AI"))))
                .andExpect(status().isNotFound());
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
