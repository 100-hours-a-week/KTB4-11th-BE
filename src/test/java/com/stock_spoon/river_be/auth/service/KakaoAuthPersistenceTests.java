package com.stock_spoon.river_be.auth.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import com.stock_spoon.river_be.auth.client.KakaoClient;
import com.stock_spoon.river_be.auth.client.KakaoUserInfo;
import com.stock_spoon.river_be.auth.exception.AuthException;
import com.stock_spoon.river_be.user.entity.OAuthProvider;
import com.stock_spoon.river_be.user.repository.UserOAuthRepository;
import com.stock_spoon.river_be.user.repository.UserRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "kakao.client-id=test-app",
        "kakao.redirect-uri=http://localhost:3000/callback"
})
@Transactional
class KakaoAuthPersistenceTests {
    @Autowired KakaoAuthService service;
    @Autowired UserRepository users;
    @Autowired UserOAuthRepository oauthUsers;
    @Autowired EntityManager entityManager;
    @MockitoBean KakaoClient kakao;

    @Test
    void firstLoginCreatesOneUserAndLaterLoginSynchronizesNickname() {
        when(kakao.verifyUser("first"))
                .thenReturn(new KakaoUserInfo(123L, "첫닉네임"));
        service.verify("first");

        assertThat(users.count()).isEqualTo(1);
        assertThat(oauthUsers.count()).isEqualTo(1);
        var oauth = oauthUsers.findByProviderAndProviderUserId(OAuthProvider.KAKAO, 123L)
                .orElseThrow();
        assertThat(oauth.getUser().getNickname()).isEqualTo("첫닉네임");

        when(kakao.verifyUser("again"))
                .thenReturn(new KakaoUserInfo(123L, "변경된닉네임"));
        service.verify("again");

        users.flush();
        entityManager.clear();
        assertThat(users.count()).isEqualTo(1);
        assertThat(oauthUsers.count()).isEqualTo(1);
        var updated = oauthUsers.findByProviderAndProviderUserId(OAuthProvider.KAKAO, 123L)
                .orElseThrow();
        assertThat(updated.getUser().getNickname()).isEqualTo("변경된닉네임");
    }

    @Test
    void missingNicknameDoesNotCreateDatabaseRows() {
        when(kakao.verifyUser("without-nickname"))
                .thenReturn(new KakaoUserInfo(456L, null));

        assertThatThrownBy(() -> service.verify("without-nickname"))
                .isInstanceOf(AuthException.class);
        assertThat(users.count()).isZero();
        assertThat(oauthUsers.count()).isZero();
    }

    @Test
    void differentKakaoUsersMayUseTheSameNickname() {
        when(kakao.verifyUser("first"))
                .thenReturn(new KakaoUserInfo(100L, "같은닉네임"));
        when(kakao.verifyUser("second"))
                .thenReturn(new KakaoUserInfo(200L, "같은닉네임"));

        service.verify("first");
        service.verify("second");

        assertThat(users.count()).isEqualTo(2);
        assertThat(oauthUsers.count()).isEqualTo(2);
    }
}
