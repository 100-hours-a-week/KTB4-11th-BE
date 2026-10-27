package com.stock_spoon.river_be.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {
    @Bean
    SecretKey jwtSecretKey(JwtProperties properties) {
        byte[] secret = properties.secret() == null
                ? new byte[0]
                : properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("JWT_SECRET은 32바이트 이상이어야 합니다.");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return NimbusJwtEncoder.withSecretKey(key)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    // [회원 탈퇴 수정] JWT가 아직 만료되지 않았어도 DB에서 삭제된 일반 회원이면 인증하지 않는다.
    @Bean("accessJwtDecoder")
    JwtDecoder accessJwtDecoder(SecretKey key, JwtProperties properties,
            com.stock_spoon.river_be.user.repository.UserRepository users) {
        JwtDecoder delegate = decoder(key, properties, "access");
        return token -> {
            Jwt jwt = delegate.decode(token);
            // AI 서버 토큰은 일반 회원 행이 없으므로 기존 전용 인증·권한 검사 경로를 유지한다.
            if ("AI".equals(jwt.getClaimAsString("actor")) && "ai-server".equals(jwt.getSubject())) return jwt;
            try {
                // 서명·만료·issuer·type 검증을 통과한 subject로 실제 회원 존재까지 확인한다.
                if (users.existsById(Long.parseLong(jwt.getSubject()))) return jwt;
            } catch (NumberFormatException ignored) { }
            // 숫자가 아닌 일반 회원 subject나 이미 삭제된 회원의 토큰은 모두 인증 실패로 처리한다.
            throw new org.springframework.security.oauth2.jwt.BadJwtException("회원이 존재하지 않습니다.");
        };
    }

    @Bean("refreshJwtDecoder")
    JwtDecoder refreshJwtDecoder(SecretKey key, JwtProperties properties) {
        return decoder(key, properties, "refresh");
    }

    private JwtDecoder decoder(SecretKey key, JwtProperties properties, String expectedType) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(properties.issuer());
        OAuth2TokenValidator<Jwt> tokenType = jwt -> expectedType.equals(jwt.getClaimAsString("type"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token", "JWT 종류가 올바르지 않습니다.", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaults, tokenType));
        return decoder;
    }
}
