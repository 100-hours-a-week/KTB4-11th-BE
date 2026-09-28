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

    public RefreshTokenService(RefreshTokenRepository repository, JwtTokenProvider tokenProvider) {
        this.repository = repository;
        this.tokenProvider = tokenProvider;
    }

    @Transactional
    public LoginTokens create(User user) {
        LoginTokens tokens = tokenProvider.issue(user.getId());
        repository.save(new RefreshToken(
                user, hash(tokens.refreshToken()), tokens.refreshExpiresAt()));
        return tokens;
    }

    @Transactional
    public LoginTokens rotate(String refreshToken) {
        long userId = tokenProvider.refreshUserId(refreshToken);
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
