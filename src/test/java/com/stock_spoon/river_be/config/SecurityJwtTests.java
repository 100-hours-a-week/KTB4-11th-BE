package com.stock_spoon.river_be.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AuthenticationServiceException;
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
import com.stock_spoon.river_be.auth.service.JwtUserExistenceVerifier;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
    @MockitoBean JwtUserExistenceVerifier userExistenceVerifier;
    private MockMvc mvc;
    private static final AtomicInteger PROTECTED_WRITES = new AtomicInteger();

    @BeforeEach
    void setup() {
        when(userExistenceVerifier.exists(anyLong())).thenReturn(true);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(RequestLoggingFilter.class))
                .apply(springSecurity()).build();
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

    @Test
    void missingUserBehindAValidSignatureStillReturns401() throws Exception {
        when(userExistenceVerifier.exists(55_555L)).thenReturn(false);
        mvc.perform(get("/test/protected").cookie(new Cookie("access_token", authToken("55555"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticationDatabaseFailureReturns503AndNeverEntersProtectedWriteHandler() throws Exception {
        PROTECTED_WRITES.set(0);
        doThrow(new AuthenticationServiceException("authentication DB unavailable"))
                .when(userExistenceVerifier).exists(77L);
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(RequestLoggingFilter.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            var getResult = mvc.perform(get("/test/protected").cookie(new Cookie("access_token", authToken("77"))))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_DATABASE_UNAVAILABLE"))
                    .andExpect(header().exists("X-Request-Id"))
                    .andReturn();
            String requestId = getResult.getResponse().getHeader("X-Request-Id");
            org.assertj.core.api.Assertions.assertThat(logs.list).anySatisfy(event ->
                    org.assertj.core.api.Assertions.assertThat(event.getFormattedMessage()).contains(
                            "event=http_response_audit", "requestId=" + requestId,
                            "dispatcherType=REQUEST", "status=503",
                            "exceptionType=AuthenticationServiceException"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/test/protected-write")
                            .with(csrf())
                            .cookie(new Cookie("access_token", authToken("77"))))
                    .andExpect(status().isServiceUnavailable());
            org.assertj.core.api.Assertions.assertThat(PROTECTED_WRITES.get()).isZero();
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void successfulAuthenticationLookupAllowsGetAndExecutesPostExactlyOnce() throws Exception {
        PROTECTED_WRITES.set(0);
        String token = authToken("88");

        mvc.perform(get("/test/protected").cookie(new Cookie("access_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value("88"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/test/protected-write").with(csrf()).cookie(new Cookie("access_token", token)))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(PROTECTED_WRITES.get()).isEqualTo(1);
    }

    @Test
    void errorDispatcherOutsideErrorEndpointDoesNotBypassAuthentication() throws Exception {
        mvc.perform(get("/test/error-dispatch").with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    return request;
                }).cookie(new Cookie("access_token", "malformed-token")))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verify(userExistenceVerifier, org.mockito.Mockito.never()).exists(anyLong());
    }

    @Test
    void onlyServletErrorDispatchToErrorEndpointIsPublic() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setContextPath("/app");
        request.setRequestURI("/app/error");
        request.setDispatcherType(DispatcherType.ERROR);
        org.assertj.core.api.Assertions.assertThat(SecurityConfig.isInternalErrorEndpointDispatch(request)).isTrue();

        request.setRequestURI("/app/test/protected");
        org.assertj.core.api.Assertions.assertThat(SecurityConfig.isInternalErrorEndpointDispatch(request)).isFalse();
        request.setRequestURI("/app/error");
        request.setDispatcherType(DispatcherType.REQUEST);
        org.assertj.core.api.Assertions.assertThat(SecurityConfig.isInternalErrorEndpointDispatch(request)).isFalse();
    }

    private String authToken(String subject) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(jwtProperties.issuer()).subject(subject)
                .issuedAt(now).expiresAt(now.plusSeconds(900)).claim("type", "access").claim("actor", "USER").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
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
        @GetMapping("/test/error-dispatch")
        Map<String, String> errorDispatchEndpoint() {
            return Map.of("result", "error-dispatch-allowed");
        }

        @org.springframework.web.bind.annotation.PostMapping("/test/protected-write")
        Map<String, String> protectedWrite() {
            PROTECTED_WRITES.incrementAndGet();
            return Map.of("result", "written");
        }

        @GetMapping("/test/protected")
        Map<String, String> protectedEndpoint(Authentication authentication) {
            return Map.of("user_id", authentication.getName());
        }
    }
}
