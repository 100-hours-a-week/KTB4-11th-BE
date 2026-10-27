package com.stock_spoon.river_be.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.stock_spoon.river_be.auth.entity.RefreshToken;
import com.stock_spoon.river_be.auth.exception.AuthException;
import com.stock_spoon.river_be.auth.repository.RefreshTokenRepository;
import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.auth.token.LoginTokens;
import com.stock_spoon.river_be.user.entity.User;

@Service
public class RefreshTokenService {
    private final RefreshTokenRepository repository;
    private final JwtTokenProvider tokenProvider;
    // [회원 탈퇴 추가] 발급·재발급 시 탈퇴 서비스와 동일한 회원 잠금을 사용하기 위한 저장소다.
    private final com.stock_spoon.river_be.user.repository.UserRepository users;

    // [회원 탈퇴 수정] 토큰 저장 직전 회원의 생존 여부를 다시 확인하도록 UserRepository도 주입받는다.
    public RefreshTokenService(RefreshTokenRepository repository, JwtTokenProvider tokenProvider,
            com.stock_spoon.river_be.user.repository.UserRepository users) {
        this.repository = repository;
        this.tokenProvider = tokenProvider;
        this.users = users;
    }

    @Transactional
    public LoginTokens create(User user) {
        // [회원 탈퇴 수정] 로그인 중 받았던 User가 삭제되었을 수 있어 잠금 재조회한 회원으로만 토큰을 저장한다.
        user = users.findLockedById(user.getId()).orElseThrow(this::invalidRefreshToken);
        LoginTokens tokens = tokenProvider.issue(user.getId());
        repository.save(new RefreshToken(
                user, hash(tokens.refreshToken()), tokens.refreshExpiresAt()));
        return tokens;
    }

    @Transactional
    public LoginTokens rotate(String refreshToken) {
        long userId = tokenProvider.refreshUserId(refreshToken);
        // [회원 탈퇴 수정] 탈퇴와 같은 회원 -> 토큰 잠금 순서로 재발급을 직렬화하고, 삭제된 회원은 401로 거절한다.
        users.findLockedById(userId).orElseThrow(this::invalidRefreshToken);
        RefreshToken stored = repository.findByTokenHash(hash(refreshToken))
                .filter(token -> token.getUser().getId().equals(userId))
                .orElseThrow(this::invalidRefreshToken);

        User user = stored.getUser();
        repository.delete(stored);
        LoginTokens tokens = tokenProvider.issue(userId);
        repository.save(new RefreshToken(
                user, hash(tokens.refreshToken()), tokens.refreshExpiresAt()));
        return tokens;
    }

    @Transactional
    public void revoke(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            repository.deleteByTokenHash(hash(refreshToken));
        }
    }

    private String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", error);
        }
    }

    private AuthException invalidRefreshToken() {
        return new AuthException(HttpStatus.UNAUTHORIZED,
                "INVALID_REFRESH_TOKEN", "Refresh Token이 유효하지 않습니다.");
    }
}
