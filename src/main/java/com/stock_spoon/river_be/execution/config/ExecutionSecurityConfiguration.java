package com.stock_spoon.river_be.execution.config;

import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.web.SecurityFilterChain;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

@Configuration(proxyBeanMethods = false)
@Profile("execution")
public class ExecutionSecurityConfiguration {
    @Bean @Order(0)
    SecurityFilterChain executionSecurity(HttpSecurity http, @Value("${execution.secret:}") String secret) throws Exception {
        if (secret.isBlank()) throw new IllegalStateException("체결 서버 전용 비밀키를 설정하세요.");
        byte[] expected = secret.getBytes(StandardCharsets.UTF_8);
        http.csrf(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(value -> value.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/internal/execution/**").access((authentication, context) -> {
                    var headers = Collections.list(context.getRequest().getHeaders("X-Execution-Key"));
                    return new AuthorizationDecision(headers.size() == 1
                            && MessageDigest.isEqual(expected, headers.getFirst().getBytes(StandardCharsets.UTF_8)));
                }).anyRequest().denyAll())
                .exceptionHandling(value -> value.authenticationEntryPoint((req, res, error) -> res.setStatus(401))
                        .accessDeniedHandler((req, res, error) -> res.setStatus(401)));
        return http.build();
    }
}
