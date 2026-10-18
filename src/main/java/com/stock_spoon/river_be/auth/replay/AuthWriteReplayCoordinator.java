package com.stock_spoon.river_be.auth.replay;

import com.stock_spoon.river_be.auth.client.KakaoUserInfo;
import com.stock_spoon.river_be.auth.service.RefreshTokenService;
import com.stock_spoon.river_be.dbwrite.DbWriteReplayDeferredException;
import com.stock_spoon.river_be.dbwrite.DbWriteReplayService;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Keeps authentication-specific replay policy outside the authentication use-case service. */
@Component
public class AuthWriteReplayCoordinator {
    private static final String LOGIN_DEFERRED_MESSAGE =
            "로그인 정보 저장을 보관했습니다. DB 전환이 끝난 뒤 다시 로그인하세요.";
    private static final String TOKEN_DEFERRED_MESSAGE =
            "인증 요청을 보관했습니다. DB 전환이 끝난 뒤 다시 로그인하세요.";

    private final DbWriteReplayService replay;
    private final RefreshTokenService refreshTokens;

    public AuthWriteReplayCoordinator(DbWriteReplayService replay, RefreshTokenService refreshTokens) {
        this.replay = replay;
        this.refreshTokens = refreshTokens;
    }

    public <T> T withProfileSyncFallback(KakaoUserInfo profile, Supplier<T> action) {
        var payload = new DbWriteReplayService.AuthProfileSyncPayload(
                profile.id(), profile.nickname(), profile.profileImageUrl());
        return execute(DbWriteReplayService.Operation.AUTH_PROFILE_SYNC, payload,
                LOGIN_DEFERRED_MESSAGE, action);
    }

    public <T> T withRefreshTokenReissueFallback(String refreshToken, Supplier<T> action) {
        return execute(DbWriteReplayService.Operation.AUTH_REFRESH_TOKEN_REVOKE,
                () -> new DbWriteReplayService.AuthRefreshTokenRevokePayload(
                        refreshTokens.hashForReplay(refreshToken)),
                TOKEN_DEFERRED_MESSAGE, action);
    }

    public void withLogoutFallback(String refreshToken, Runnable action) {
        if (refreshToken == null || refreshToken.isBlank()) {
            action.run();
            return;
        }
        execute(DbWriteReplayService.Operation.AUTH_REFRESH_TOKEN_REVOKE,
                () -> new DbWriteReplayService.AuthRefreshTokenRevokePayload(
                        refreshTokens.hashForReplay(refreshToken)),
                TOKEN_DEFERRED_MESSAGE, () -> {
                    action.run();
                    return null;
                });
    }

    private <T> T execute(DbWriteReplayService.Operation operation, Object payload,
            String responseMessage, Supplier<T> action) {
        return execute(operation, () -> payload, responseMessage, action);
    }

    private <T> T execute(DbWriteReplayService.Operation operation, Supplier<Object> payload,
            String responseMessage, Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException failure) {
            var requestId = replay.enqueueIfWriterUnavailable(failure, operation, payload.get());
            if (requestId.isPresent()) {
                throw new DbWriteReplayDeferredException(requestId.get(), responseMessage);
            }
            throw failure;
        }
    }
}
