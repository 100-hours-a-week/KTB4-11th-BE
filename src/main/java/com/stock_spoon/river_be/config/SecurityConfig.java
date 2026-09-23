package com.stock_spoon.river_be.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.client.RestClient;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableConfigurationProperties(KakaoProperties.class)
public class SecurityConfig {
    @Bean
    RestClient kakaoRestClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    BearerTokenResolver accessTokenResolver() {
        return request -> {
            if (request.getRequestURI().startsWith("/api/v1/auth/")) {
                return null;
            }
            return request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                    .filter(cookie -> "access_token".equals(cookie.getName()))
                    .map(Cookie::getValue)
                    .filter(value -> !value.isBlank())
                    .findFirst()
                    .orElse(null);
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, KakaoProperties properties,
            BearerTokenResolver accessTokenResolver,
            @Qualifier("accessJwtDecoder") JwtDecoder accessJwtDecoder) throws Exception {
        var csrf = new CookieCsrfTokenRepository();
        csrf.setCookieCustomizer(cookie -> cookie.httpOnly(true)
                .secure(properties.secureCookie()).sameSite("Lax").path("/"));
        var cors = new CorsConfiguration();
        if (properties.frontendOrigin() != null && !properties.frontendOrigin().isBlank()) {
            cors.setAllowedOrigins(List.of(properties.frontendOrigin()));
        }
        cors.setAllowedMethods(List.of("GET", "POST"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        cors.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        http.cors(config -> config.configurationSource(source))
                .csrf(config -> config.csrfTokenRepository(csrf))
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login",
                                "/api/v1/auth/reissue", "/api/v1/auth/logout").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(accessTokenResolver)
                        .jwt(jwt -> jwt.decoder(accessJwtDecoder))
                        .authenticationEntryPoint((request, response, error) -> writeError(response, 401,
                                "UNAUTHORIZED", "로그인이 필요합니다.")))
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, error) -> writeError(response, 401,
                                "UNAUTHORIZED", "로그인이 필요합니다."))
                        .accessDeniedHandler((request, response, error) -> writeError(response, 403,
                                error instanceof org.springframework.security.web.csrf.CsrfException
                                        ? "INVALID_CSRF_TOKEN" : "FORBIDDEN",
                                "요청 권한 또는 CSRF 확인값을 확인해주세요.")));
        return http.build();
    }

    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
