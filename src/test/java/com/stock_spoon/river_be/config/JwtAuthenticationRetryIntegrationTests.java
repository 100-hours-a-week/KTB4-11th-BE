package com.stock_spoon.river_be.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/** Real signed JWT, security chain and retry verifier; only the repository failure is injected. */
@SpringBootTest(properties = {"JWT_AUTH_DB_RETRY_DELAY_MS=0", "JWT_AUTH_DB_MAX_RETRIES=2"})
@Import(JwtAuthenticationRetryIntegrationTests.ProbeController.class)
class JwtAuthenticationRetryIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired JwtEncoder encoder;
    @Autowired JwtProperties properties;
    @Autowired ProbeController handler;
    @MockitoBean UserRepository users;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(users);
        handler.calls.set(0);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @ParameterizedTest
    @CsvSource({"GET,false", "POST,false", "GET,true", "POST,true"})
    void transientDatabaseFailureRecoversForOrdinaryAndK6Tokens(String method, boolean k6) throws Exception {
        when(users.existsById(42L)).thenThrow(connectionFailure()).thenReturn(true);

        mvc.perform(request(method).cookie(new Cookie("access_token", token(k6))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ok"));

        verify(users, times(2)).existsById(42L);
        assertThat(handler.calls).hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"GET,false", "POST,false", "GET,true", "POST,true"})
    void exhaustedDatabaseFailureIs503AndNeverRunsBusinessHandler(String method, boolean k6) throws Exception {
        when(users.existsById(42L)).thenThrow(connectionFailure());

        mvc.perform(request(method).cookie(new Cookie("access_token", token(k6))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_DATABASE_UNAVAILABLE"));

        verify(users, times(3)).existsById(42L);
        assertThat(handler.calls).hasValue(0);
    }

    @ParameterizedTest
    @CsvSource({"GET,false", "POST,false", "GET,true", "POST,true"})
    void deletedUserIs401WithoutRetryOrBusinessExecution(String method, boolean k6) throws Exception {
        when(users.existsById(42L)).thenReturn(false);

        mvc.perform(request(method).cookie(new Cookie("access_token", token(k6))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(users).existsById(42L);
        assertThat(handler.calls).hasValue(0);
    }

    @Test
    void invalidSignatureIsRejectedBeforeDatabaseLookup() throws Exception {
        String signed = token(false);
        String[] parts = signed.split("\\.");
        String signature = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);

        mvc.perform(get("/test/auth-retry").cookie(new Cookie("access_token", parts[0]+"."+parts[1]+"."+signature)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(users);
        assertThat(handler.calls).hasValue(0);
    }

    private MockHttpServletRequestBuilder request(String method) {
        return method.equals("POST") ? post("/test/auth-retry").with(csrf()) : get("/test/auth-retry");
    }

    private String token(boolean k6) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(properties.issuer()).subject("42")
                .issuedAt(now).expiresAt(now.plusSeconds(600)).claim("type", "access").claim("actor", "USER");
        if (k6) claims.claim("k6_test", true);
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    private static DataAccessResourceFailureException connectionFailure() {
        return new DataAccessResourceFailureException("injected connection failure",
                new SQLException("Communications link failure", "08S01", 0));
    }

    @RestController
    static class ProbeController {
        final AtomicInteger calls = new AtomicInteger();
        @GetMapping("/test/auth-retry")
        Map<String, String> read() { calls.incrementAndGet(); return Map.of("result", "ok"); }
        @PostMapping("/test/auth-retry")
        Map<String, String> write() { calls.incrementAndGet(); return Map.of("result", "ok"); }
    }
}
