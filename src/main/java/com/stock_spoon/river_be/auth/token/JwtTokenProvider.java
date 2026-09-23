package com.stock_spoon.river_be.auth.token;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Component;
import com.stock_spoon.river_be.auth.exception.AuthException;
import com.stock_spoon.river_be.config.JwtProperties;

@Component
public class JwtTokenProvider {
    private final JwtEncoder encoder;
    private final JwtDecoder refreshDecoder;
    private final JwtProperties properties;
    private final Clock clock = Clock.systemUTC();

    public JwtTokenProvider(JwtEncoder encoder,
            @Qualifier("refreshJwtDecoder") JwtDecoder refreshDecoder,
            JwtProperties properties) {
        this.encoder = encoder;
        this.refreshDecoder = refreshDecoder;
        this.properties = properties;
    }

    public LoginTokens issue(long userId) {
        Instant now = clock.instant();
        Instant accessExpiresAt = now.plus(properties.accessExpiration());
        Instant refreshExpiresAt = now.plus(properties.refreshExpiration());
        return new LoginTokens(
                encode(userId, "access", now, accessExpiresAt),
                encode(userId, "refresh", now, refreshExpiresAt),
                refreshExpiresAt);
    }

    public long refreshUserId(String token) {
        try {
            String subject = refreshDecoder.decode(token).getSubject();
            long userId = Long.parseLong(subject);
            if (userId <= 0) {
                throw invalidRefreshToken();
            }
            return userId;
        } catch (JwtException | NumberFormatException error) {
            throw invalidRefreshToken();
        }
    }

    private String encode(long userId, String type, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(Long.toString(userId))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("type", type)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private AuthException invalidRefreshToken() {
        return new AuthException(HttpStatus.UNAUTHORIZED,
                "INVALID_REFRESH_TOKEN", "Refresh Token이 유효하지 않습니다.");
    }
}
