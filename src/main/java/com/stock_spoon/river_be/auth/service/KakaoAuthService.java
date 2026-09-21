package com.stock_spoon.river_be.auth.service;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.stock_spoon.river_be.auth.client.KakaoClient;
import com.stock_spoon.river_be.auth.exception.AuthException;
import com.stock_spoon.river_be.config.KakaoProperties;
import com.stock_spoon.river_be.user.entity.OAuthProvider;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.entity.UserOAuth;
import com.stock_spoon.river_be.user.repository.UserOAuthRepository;
import com.stock_spoon.river_be.user.repository.UserRepository;

// 인증 처리 순서를 관리
@Service
public class KakaoAuthService {
    private final KakaoClient client;
    private final KakaoProperties properties;
    private final UserRepository userRepository;
    private final UserOAuthRepository userOAuthRepository;

    public KakaoAuthService(KakaoClient client, KakaoProperties properties,
            UserRepository userRepository, UserOAuthRepository userOAuthRepository) {
        this.client = client;
        this.properties = properties;
        this.userRepository = userRepository;
        this.userOAuthRepository = userOAuthRepository;
    }

    @Transactional
    public User verify(String code) {
        requireConfiguration();
        var kakaoUser = client.verifyUser(code);
        if (kakaoUser.nickname() == null || kakaoUser.nickname().isBlank()) {
            throw new AuthException(HttpStatus.BAD_GATEWAY,
                    "KAKAO_NICKNAME_NOT_PROVIDED", "카카오에서 닉네임을 제공받지 못했습니다.");
        }

        var oauth = userOAuthRepository.findByProviderAndProviderUserId(
                OAuthProvider.KAKAO, kakaoUser.id());
        if (oauth.isPresent()) {
            User user = oauth.get().getUser();
            user.synchronizeNickname(kakaoUser.nickname());
            return user;
        }

        User user = userRepository.save(new User(kakaoUser.nickname()));
        userOAuthRepository.save(new UserOAuth(user, OAuthProvider.KAKAO, kakaoUser.id()));
        return user;
    }

    private void requireConfiguration() {
        boolean valid = properties.clientId() != null && !properties.clientId().isBlank()
                && properties.redirectUri() != null && !properties.redirectUri().isBlank();
        try {
            URI uri = URI.create(properties.redirectUri() == null ? "" : properties.redirectUri());
            valid &= uri.getHost() != null && ("https".equals(uri.getScheme())
                    || ("http".equals(uri.getScheme()) && "localhost".equals(uri.getHost())));
        } catch (IllegalArgumentException error) {
            valid = false;
        }
        if (!valid) {
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OAUTH_NOT_CONFIGURED", "카카오 앱 키와 Redirect URI 설정이 필요합니다.");
        }
    }
}
