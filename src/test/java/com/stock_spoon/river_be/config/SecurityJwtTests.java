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

    @RestController
    static class ProtectedController {
        @GetMapping("/test/protected")
        Map<String, String> protectedEndpoint(Authentication authentication) {
            return Map.of("user_id", authentication.getName());
        }
    }
}
