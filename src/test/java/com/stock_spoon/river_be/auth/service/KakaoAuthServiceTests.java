package com.stock_spoon.river_be.auth.service;

import org.junit.jupiter.api.Test;
import com.stock_spoon.river_be.auth.client.KakaoClient;
import com.stock_spoon.river_be.auth.client.KakaoUserInfo;
import com.stock_spoon.river_be.auth.exception.AuthException;
import com.stock_spoon.river_be.config.KakaoProperties;
import com.stock_spoon.river_be.user.entity.OAuthProvider;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.entity.UserOAuth;
import com.stock_spoon.river_be.user.repository.UserOAuthRepository;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

// [회원 탈퇴 테스트 수정] KakaoProperties의 adminKey 인자를 추가했다. 실제 외부 연결 없이 기존 로그인 동작을 검증한다.
class KakaoAuthServiceTests {
    @Test
    void blankConfigurationFailsBeforeAnyExternalCall() {
        var client = mock(KakaoClient.class);
        var users = mock(UserRepository.class);
        var oauthUsers = mock(UserOAuthRepository.class);
        var service = new KakaoAuthService(client,
                new KakaoProperties("", "", "", "", true, ""), users, oauthUsers);
        assertThatThrownBy(() -> service.verify("code"))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.code()).isEqualTo("OAUTH_NOT_CONFIGURED"));
        verifyNoInteractions(client);
    }

    @Test
    void acceptsDevelopmentHttpCallback() {
        var client = mock(KakaoClient.class);
        var users = mock(UserRepository.class);
        var oauthUsers = mock(UserOAuthRepository.class);
        var service = new KakaoAuthService(client,
                new KakaoProperties("app", "", "http://54.180.180.202/auth/callback", "", false, "test-admin"),
                users, oauthUsers);
        var existing = new User("dev");
        // [회원 탈퇴 테스트 수정] 기존 회원 로그인도 잠금 재조회를 통과하도록 저장소 응답을 준비한다.
        when(users.findLockedById(existing.getId())).thenReturn(Optional.of(existing));
        when(client.verifyUser("code")).thenReturn(new KakaoUserInfo(123L, "dev", null));
        when(oauthUsers.findByProviderAndProviderUserId(OAuthProvider.KAKAO, 123L))
                .thenReturn(Optional.of(new UserOAuth(existing, OAuthProvider.KAKAO, 123L)));

        assertThat(service.verify("code")).isSameAs(existing);
        verify(client).verifyUser("code");
    }

    @Test
    void rejectsUnapprovedHttpCallbackBeforeExternalCall() {
        var client = mock(KakaoClient.class);
        var service = new KakaoAuthService(client,
                new KakaoProperties("app", "", "http://example.com/auth/callback", "", false, "test-admin"),
                mock(UserRepository.class), mock(UserOAuthRepository.class));
        assertThatThrownBy(() -> service.verify("code"))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.code()).isEqualTo("OAUTH_NOT_CONFIGURED"));
        verifyNoInteractions(client);
    }
    @Test
    void createsUserForFirstKakaoLogin() {
        var client = mock(KakaoClient.class);
        var users = mock(UserRepository.class);
        var oauthUsers = mock(UserOAuthRepository.class);
        var service = new KakaoAuthService(client,
                new KakaoProperties("app", "", "http://localhost:3000/callback", "", false, "test-admin"),
                users, oauthUsers);
        when(client.verifyUser("code")).thenReturn(new KakaoUserInfo(
                123L, "첫닉네임", "https://example.com/first.jpg"));
        when(oauthUsers.findByProviderAndProviderUserId(OAuthProvider.KAKAO, 123L))
                .thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = service.verify("code");

        assertThat(user.getNickname()).isEqualTo("첫닉네임");
        assertThat(user.getProfileImageUrl()).isEqualTo("https://example.com/first.jpg");
        verify(client).verifyUser("code");
        verify(users).save(any(User.class));
        verify(oauthUsers).save(any(UserOAuth.class));
    }

    @Test
    void synchronizesNicknameForExistingUser() {
        var client = mock(KakaoClient.class);
        var users = mock(UserRepository.class);
        var oauthUsers = mock(UserOAuthRepository.class);
        var service = new KakaoAuthService(client,
                new KakaoProperties("app", "", "http://localhost:3000/callback", "", false, "test-admin"),
                users, oauthUsers);
        User existingUser = new User("이전닉네임");
        // [회원 탈퇴 테스트 수정] 프로필 동기화가 잠금 재조회한 회원에게 적용되는지 검증한다.
        when(users.findLockedById(existingUser.getId())).thenReturn(Optional.of(existingUser));
        when(client.verifyUser("code")).thenReturn(new KakaoUserInfo(
                123L, "새닉네임", "https://example.com/new.jpg"));
        when(oauthUsers.findByProviderAndProviderUserId(OAuthProvider.KAKAO, 123L))
                .thenReturn(Optional.of(new UserOAuth(existingUser, OAuthProvider.KAKAO, 123L)));

        User user = service.verify("code");

        assertThat(user).isSameAs(existingUser);
        assertThat(user.getNickname()).isEqualTo("새닉네임");
        assertThat(user.getProfileImageUrl()).isEqualTo("https://example.com/new.jpg");
        verify(users, never()).save(any());
        verify(oauthUsers, never()).save(any());
    }

    @Test
    void missingNicknameFailsBeforeDatabaseChange() {
        var client = mock(KakaoClient.class);
        var users = mock(UserRepository.class);
        var oauthUsers = mock(UserOAuthRepository.class);
        var service = new KakaoAuthService(client,
                new KakaoProperties("app", "", "http://localhost:3000/callback", "", false, "test-admin"),
                users, oauthUsers);
        when(client.verifyUser("code")).thenReturn(new KakaoUserInfo(123L, null, null));

        assertThatThrownBy(() -> service.verify("code"))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.code()).isEqualTo("KAKAO_NICKNAME_NOT_PROVIDED"));
        verifyNoInteractions(users, oauthUsers);
    }
}
