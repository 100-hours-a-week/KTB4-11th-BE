package com.stock_spoon.river_be.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jwt")
public record JwtProperties(
        String secret,
        String issuer,
        Duration accessExpiration,
        Duration refreshExpiration) {
    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT_ISSUER가 필요합니다.");
        }
        if (accessExpiration == null || accessExpiration.isZero() || accessExpiration.isNegative()) {
            throw new IllegalArgumentException("JWT_ACCESS_EXPIRATION은 양수여야 합니다.");
        }
        if (refreshExpiration == null || refreshExpiration.isZero() || refreshExpiration.isNegative()) {
            throw new IllegalArgumentException("JWT_REFRESH_EXPIRATION은 양수여야 합니다.");
        }
    }
}
