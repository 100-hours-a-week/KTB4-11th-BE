package com.stock_spoon.river_be.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jwt")
public record JwtProperties(
        String secret,
        String issuer,
        Duration accessExpiration,
        Duration refreshExpiration) {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JwtProperties.class);

    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            log.error("event=jwt_configuration_failed stage=properties_validation code=JWT_ISSUER_MISSING");
            throw new IllegalArgumentException("JWT_ISSUER가 필요합니다.");
        }
        if (accessExpiration == null || accessExpiration.isZero() || accessExpiration.isNegative()) {
            log.error("event=jwt_configuration_failed stage=properties_validation code=JWT_ACCESS_EXPIRATION_INVALID");
            throw new IllegalArgumentException("JWT_ACCESS_EXPIRATION은 양수여야 합니다.");
        }
        if (refreshExpiration == null || refreshExpiration.isZero() || refreshExpiration.isNegative()) {
            log.error("event=jwt_configuration_failed stage=properties_validation code=JWT_REFRESH_EXPIRATION_INVALID");
            throw new IllegalArgumentException("JWT_REFRESH_EXPIRATION은 양수여야 합니다.");
        }
    }
}
