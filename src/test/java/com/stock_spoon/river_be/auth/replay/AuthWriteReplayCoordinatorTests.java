package com.stock_spoon.river_be.auth.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stock_spoon.river_be.auth.client.KakaoUserInfo;
import com.stock_spoon.river_be.auth.service.RefreshTokenService;
import com.stock_spoon.river_be.dbwrite.DbWriteReplayDeferredException;
import com.stock_spoon.river_be.dbwrite.DbWriteReplayService;
import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthWriteReplayCoordinatorTests {
    @Test
    void profileWriteFailureQueuesProfileCommandAndReturnsDeferredRequestId() {
        var replay = mock(DbWriteReplayService.class);
        var refreshTokens = mock(RefreshTokenService.class);
        var coordinator = new AuthWriteReplayCoordinator(replay, refreshTokens);
        var profile = new KakaoUserInfo(123L, "nickname", "https://example.com/profile.jpg");
        var failure = writerUnavailable();
        when(replay.enqueueIfWriterUnavailable(failure, DbWriteReplayService.Operation.AUTH_PROFILE_SYNC,
                new DbWriteReplayService.AuthProfileSyncPayload(123L, "nickname", "https://example.com/profile.jpg")))
                .thenReturn(Optional.of("request-123"));

        assertThatThrownBy(() -> coordinator.withProfileSyncFallback(profile, () -> {
            throw failure;
        })).isInstanceOfSatisfying(DbWriteReplayDeferredException.class, deferred -> {
            assertThat(deferred.requestId()).isEqualTo("request-123");
            assertThat(deferred.getMessage()).contains("다시 로그인");
        });
    }

    @Test
    void tokenRotationFailureQueuesOnlyTheHashedToken() {
        var replay = mock(DbWriteReplayService.class);
        var refreshTokens = mock(RefreshTokenService.class);
        var coordinator = new AuthWriteReplayCoordinator(replay, refreshTokens);
        var failure = writerUnavailable();
        when(refreshTokens.hashForReplay("refresh-token")).thenReturn("sha256-token-hash");
        when(replay.enqueueIfWriterUnavailable(failure,
                DbWriteReplayService.Operation.AUTH_REFRESH_TOKEN_REVOKE,
                new DbWriteReplayService.AuthRefreshTokenRevokePayload("sha256-token-hash")))
                .thenReturn(Optional.of("request-456"));

        assertThatThrownBy(() -> coordinator.withRefreshTokenReissueFallback("refresh-token", () -> {
            throw failure;
        })).isInstanceOfSatisfying(DbWriteReplayDeferredException.class, deferred -> {
            assertThat(deferred.requestId()).isEqualTo("request-456");
            assertThat(deferred.getMessage()).contains("다시 로그인");
        });
        verify(refreshTokens).hashForReplay("refresh-token");
    }

    @Test
    void logoutWithoutRefreshTokenDoesNotCreateReplayCommand() {
        var replay = mock(DbWriteReplayService.class);
        var refreshTokens = mock(RefreshTokenService.class);
        var coordinator = new AuthWriteReplayCoordinator(replay, refreshTokens);

        coordinator.withLogoutFallback(null, () -> {});

        org.mockito.Mockito.verifyNoInteractions(replay, refreshTokens);
    }

    private static RuntimeException writerUnavailable() {
        return new RuntimeException(new SQLException(
                "The MySQL server is running with the --super-read-only option", "HY000", 1290));
    }
}
