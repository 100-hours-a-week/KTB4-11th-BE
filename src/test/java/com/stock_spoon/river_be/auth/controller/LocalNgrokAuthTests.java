package com.stock_spoon.river_be.auth.controller;

import com.stock_spoon.river_be.auth.client.KakaoClient;
import com.stock_spoon.river_be.auth.client.KakaoUserInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "kakao.client-id=test-app",
        "kakao.redirect-uri=http://localhost:3000/callback",
        "AUTH_CSRF_DISABLED=true",
        "AUTH_COOKIE_SAME_SITE=None",
        "kakao.secure-cookie=true"})
@Transactional
class LocalNgrokAuthTests {
    @Autowired WebApplicationContext context;
    @MockitoBean KakaoClient kakao;

    @Test
    void loginWorksWithoutCsrfAndSetsCrossSiteCookies() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk());
        when(kakao.verifyUser("test-code")).thenReturn(new KakaoUserInfo(987L, "테스트", null));

        var response = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorization_code\":\"test-code\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getHeaders("Set-Cookie"))
                .hasSize(2)
                .allSatisfy(header -> assertThat(header).contains("SameSite=None", "Secure", "HttpOnly"));
    }
}
