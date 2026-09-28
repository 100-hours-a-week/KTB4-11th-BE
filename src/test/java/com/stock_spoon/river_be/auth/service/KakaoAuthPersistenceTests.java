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
    void firstLoginCreatesOneUserAndLaterLoginSynchronizesProfile() {
        when(kakao.verifyUser("first"))
                .thenReturn(new KakaoUserInfo(
                        123L, "첫닉네임", "https://example.com/first.jpg"));
        service.verify("first");

        assertThat(users.count()).isEqualTo(1);
        assertThat(oauthUsers.count()).isEqualTo(1);
        var oauth = oauthUsers.findByProviderAndProviderUserId(OAuthProvider.KAKAO, 123L)
                .orElseThrow();
        assertThat(oauth.getUser().getNickname()).isEqualTo("첫닉네임");
        assertThat(oauth.getUser().getProfileImageUrl())
                .isEqualTo("https://example.com/first.jpg");

        when(kakao.verifyUser("again"))
                .thenReturn(new KakaoUserInfo(
                        123L, "변경된닉네임", "https://example.com/changed.jpg"));
        service.verify("again");

        users.flush();
        entityManager.clear();
        assertThat(users.count()).isEqualTo(1);
        assertThat(oauthUsers.count()).isEqualTo(1);
        var updated = oauthUsers.findByProviderAndProviderUserId(OAuthProvider.KAKAO, 123L)
                .orElseThrow();
        assertThat(updated.getUser().getNickname()).isEqualTo("변경된닉네임");
        assertThat(updated.getUser().getProfileImageUrl())
                .isEqualTo("https://example.com/changed.jpg");

        when(kakao.verifyUser("without-image"))
                .thenReturn(new KakaoUserInfo(123L, "이미지없는닉네임", null));
        service.verify("without-image");
        users.flush();
        entityManager.clear();

        var withoutImage = oauthUsers.findByProviderAndProviderUserId(OAuthProvider.KAKAO, 123L)
                .orElseThrow();
        assertThat(withoutImage.getUser().getNickname()).isEqualTo("이미지없는닉네임");
        assertThat(withoutImage.getUser().getProfileImageUrl()).isNull();
    }

    @Test
    void missingNicknameDoesNotCreateDatabaseRows() {
        when(kakao.verifyUser("without-nickname"))
                .thenReturn(new KakaoUserInfo(456L, null, "https://example.com/profile.jpg"));

        assertThatThrownBy(() -> service.verify("without-nickname"))
                .isInstanceOf(AuthException.class);
        assertThat(users.count()).isZero();
        assertThat(oauthUsers.count()).isZero();
    }

    @Test
    void differentKakaoUsersMayUseTheSameNickname() {
        when(kakao.verifyUser("first"))
                .thenReturn(new KakaoUserInfo(100L, "같은닉네임", null));
        when(kakao.verifyUser("second"))
                .thenReturn(new KakaoUserInfo(200L, "같은닉네임", null));

        service.verify("first");
        service.verify("second");

        assertThat(users.count()).isEqualTo(2);
        assertThat(oauthUsers.count()).isEqualTo(2);
    }
}
