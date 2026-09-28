package com.stock_spoon.river_be.auth.token;

import java.time.Instant;

public record LoginTokens(
        String accessToken,
        String refreshToken,
        Instant refreshExpiresAt) {
}
