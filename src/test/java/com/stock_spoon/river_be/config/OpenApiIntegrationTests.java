package com.stock_spoon.river_be.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"springdoc.swagger-ui.with-credentials=true", "springdoc.swagger-ui.csrf.enabled=true", "springdoc.paths-to-match=/api/**"})
class OpenApiIntegrationTests {
    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void documentsArePublicAndDescribeRealApiAndCookieAuthentication() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Stock Spoon API"))
                .andExpect(jsonPath("$.paths['/api/v2/stocks/{stockCode}'].get.summary").value("종목명 조회"))
                .andExpect(jsonPath("$.components.securitySchemes.accessCookie.in").value("cookie"))
                .andExpect(jsonPath("$.components.securitySchemes.accessCookie.name").value("access_token"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").isEmpty());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/swagger-initializer.js")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("requestInterceptor: async function")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("csrf.header_name")));
        mvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withCredentials").value(true));
    }

    @Test void documentationDoesNotOpenProtectedApiOrDisableCsrf() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content("{\"authorization_code\":\"test\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
    }

    @Test void maskedCsrfTokenAndHttpOnlyCookiePassCsrfValidation() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk())
                .andReturn().getResponse();
        String token = com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.token");
        var cookie = response.getCookie("XSRF-TOKEN");
        org.junit.jupiter.api.Assertions.assertNotNull(cookie);
        org.junit.jupiter.api.Assertions.assertTrue(cookie.isHttpOnly());
        // No refresh cookie: authentication fails, but the real CSRF filter accepts the pair.
        mvc.perform(post("/api/v1/auth/reissue").cookie(cookie).header("X-XSRF-TOKEN", token))
                .andExpect(status().isUnauthorized());
    }
}
