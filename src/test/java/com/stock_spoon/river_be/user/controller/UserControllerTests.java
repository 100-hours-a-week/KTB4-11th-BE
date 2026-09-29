package com.stock_spoon.river_be.user.controller;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class UserControllerTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void returnsCurrentDatabaseProfileOfAuthenticatedUser() throws Exception {
        var me = users.save(new User("이전 닉네임", null));
        var cookie = accessCookie(me.getId());
        var other = users.save(new User("다른 사용자", "https://example.com/other.jpg"));
        me.synchronizeProfile("현재 닉네임", "https://example.com/me.jpg");
        users.flush();

        mvc.perform(get("/api/v1/users/me").cookie(cookie)
                        .param("user_id", other.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"nickname":"현재 닉네임","profile_image_url":"https://example.com/me.jpg"}
                        """));
    }

    @Test
    void returnsExplicitNullWhenProfileImageIsAbsent() throws Exception {
        var me = users.save(new User("닉네임"));
        mvc.perform(get("/api/v1/users/me").cookie(accessCookie(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("닉네임"))
                .andExpect(jsonPath("$.profile_image_url").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.hasKey("profile_image_url")));
    }

    @Test
    void rejectsUnauthenticatedRequest() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsRefreshTokenAsAccessToken() throws Exception {
        var me = users.save(new User("닉네임"));
        mvc.perform(get("/api/v1/users/me")
                        .cookie(new Cookie("access_token", tokens.issue(me.getId()).refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsNotFoundWhenTokenUserNoLongerExists() throws Exception {
        var me = users.save(new User("탈퇴 사용자"));
        var cookie = accessCookie(me.getId());
        users.delete(me);
        users.flush();
        mvc.perform(get("/api/v1/users/me").cookie(cookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    private Cookie accessCookie(long userId) {
        return new Cookie("access_token", tokens.issue(userId).accessToken());
    }
}
