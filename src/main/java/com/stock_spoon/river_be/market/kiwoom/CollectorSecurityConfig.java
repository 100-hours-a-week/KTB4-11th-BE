package com.stock_spoon.river_be.market.kiwoom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class CollectorSecurityConfig {
    public static final String SECRET_HEADER = "X-Kiwoom-Collector-Key";

    @Bean
    @Order(1)
    SecurityFilterChain collectorSecurity(HttpSecurity http,
            @Value("${kiwoom.collector.enabled:false}") boolean collector,
            @Value("${kiwoom.collector.secret:}") String secret) throws Exception {
        if (collector && secret.isBlank()) throw new IllegalStateException("수집 담당 서버의 전용 비밀키를 설정하세요.");
        byte[] expected = secret.getBytes(StandardCharsets.UTF_8);
        http.securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().access((authentication, context) -> {
                    var headers = Collections.list(context.getRequest().getHeaders(SECRET_HEADER));
                    boolean allowed = collector && expected.length > 0 && headers.size() == 1
                            && MessageDigest.isEqual(expected, headers.getFirst().getBytes(StandardCharsets.UTF_8));
                    return new AuthorizationDecision(allowed);
                }))
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, error) -> response.setStatus(401))
                        .accessDeniedHandler((request, response, error) -> response.setStatus(401)));
        return http.build();
    }
}
