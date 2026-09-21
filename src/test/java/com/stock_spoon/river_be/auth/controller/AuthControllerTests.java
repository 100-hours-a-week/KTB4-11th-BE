package com.stock_spoon.river_be.auth.controller;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.stock_spoon.river_be.auth.client.KakaoClient;
import com.stock_spoon.river_be.auth.client.KakaoUserInfo;
import com.stock_spoon.river_be.auth.exception.AuthException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "kakao.client-id=test-app", "kakao.redirect-uri=http://localhost:3000/callback",
        "kakao.frontend-origin=http://localhost:3000", "kakao.secure-cookie=false"})
class AuthControllerTests {
    @Autowired WebApplicationContext context;
    @MockitoBean KakaoClient kakao;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor validCsrf() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String token = JsonPath.read(response.getContentAsString(), "$.token");
        Cookie cookie = response.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return request -> {
            request.setCookies(cookie);
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }
    @Test
    void codeOnlyWithRealCsrfCookieAndHeaderSucceedsWithoutOAuthPreparation() throws Exception {
        var csrfResponse = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String csrfToken = JsonPath.read(csrfResponse.getContentAsString(), "$.token");
        Cookie csrfCookie = csrfResponse.getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.isHttpOnly()).isTrue();
        when(kakao.verifyUser("test-code")).thenReturn(
                new KakaoUserInfo(123L, "카카오닉네임"));

        var response = mvc.perform(post("/api/v1/auth/login")
                        .cookie(csrfCookie).header("X-XSRF-TOKEN", csrfToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorization_code\":\"test-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("KAKAO_AUTH_VERIFIED"))
                .andExpect(jsonPath("$.access_token").doesNotExist())
                .andExpect(jsonPath("$.user_id").doesNotExist())
                .andReturn().getResponse();
        assertThat(response.getCookie("oauth_attempt")).isNull();
        assertThat(response.getCookie("access_token")).isNull();
        verify(kakao).verifyUser("test-code");
        verifyNoMoreInteractions(kakao);
    }

    @Test
    void missingCsrfIsRejectedBeforeKakaoCall() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorization_code\":\"code\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        verifyNoInteractions(kakao);
    }

    @Test
    void wrongCsrfHeaderIsRejectedEvenWhenCookieExists() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk()).andReturn().getResponse();
        mvc.perform(post("/api/v1/auth/login")
                        .cookie(response.getCookie("XSRF-TOKEN"))
                        .header("X-XSRF-TOKEN", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorization_code\":\"code\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        verifyNoInteractions(kakao);
    }

    @Test
    void blankCodeIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(validCsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorization_code\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(kakao);
    }

    @Test
    void missingCodeIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(validCsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(kakao);
    }

    @Test
    void rejectedAuthorizationCodeDoesNotBecomeSuccessfulVerification() throws Exception {
        when(kakao.verifyUser("used-code")).thenThrow(new AuthException(
                HttpStatus.BAD_REQUEST, "INVALID_AUTHORIZATION_CODE", "유효하지 않은 인가코드입니다."));
        mvc.perform(post("/api/v1/auth/login").with(validCsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorization_code\":\"used-code\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUTHORIZATION_CODE"));
    }

    @Test
    void removedPreparationEndpointIsNotPermittedOrMapped() throws Exception {
        mvc.perform(post("/api/v1/auth/kakao/prepare").with(validCsrf()))
                .andExpect(status().isUnauthorized());
        var mappings = context.getBean("requestMappingHandlerMapping",
                org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        assertThat(mappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream()))
                .doesNotContain("/api/v1/auth/kakao/prepare");
        verifyNoInteractions(kakao);
    }

    @Test
    void unapprovedOriginIsRejected() throws Exception {
        mvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvedFrontendCanSendCsrfHeaderAndCookies() throws Exception {
        mvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void unrelatedApiIsNotOpenedByKakaoVerification() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }
}
