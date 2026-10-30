package com.stock_spoon.river_be.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.client.RestClient;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import com.stock_spoon.river_be.auth.service.JwtUserExistenceVerifier;

@Configuration
@EnableConfigurationProperties(KakaoProperties.class)
public class SecurityConfig {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SecurityConfig.class);
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
            if (request.getDispatcherType() == DispatcherType.ERROR) {
                return null;
            }
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
            @Qualifier("accessJwtDecoder") JwtDecoder accessJwtDecoder,
            JwtUserExistenceVerifier jwtUserExistenceVerifier,
            @Value("${AUTH_CSRF_DISABLED:false}") boolean csrfDisabled,
            @Value("${AUTH_COOKIE_SAME_SITE:Lax}") String cookieSameSite) throws Exception {
        var csrf = new CookieCsrfTokenRepository();
        csrf.setCookieCustomizer(cookie -> cookie.httpOnly(true)
                .secure(properties.secureCookie()).sameSite(cookieSameSite).path("/"));
        var cors = new CorsConfiguration();
        if (properties.frontendOrigin() != null && !properties.frontendOrigin().isBlank()) {
            cors.setAllowedOrigins(Arrays.stream(properties.frontendOrigin().split(","))
                    .map(String::trim)
                    .filter(origin -> !origin.isEmpty())
                    .toList());
        }
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "ngrok-skip-browser-warning", "Idempotency-Key"));
        cors.setAllowCredentials(true);
        cors.setExposedHeaders(List.of("X-Request-Id"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        http.cors(config -> config.configurationSource(source))
                .csrf(config -> {
                    config.csrfTokenRepository(csrf);
                    if (csrfDisabled) config.ignoringRequestMatchers("/api/**");
                })
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                        .requestMatchers(SecurityConfig::isInternalErrorEndpointDispatch).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login",
                                "/api/v1/auth/reissue", "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/ai-server")
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            boolean allowed = current instanceof JwtAuthenticationToken token
                                    && "AI".equals(token.getToken().getClaimAsString("actor"))
                                    && "ai-server".equals(token.getToken().getSubject());
                            return new AuthorizationDecision(allowed);
                        })
                        .anyRequest().access((authentication, context) -> {
                            var current = authentication.get();
                            boolean allowed = current instanceof JwtAuthenticationToken token
                                    && !"ai-server".equals(token.getToken().getSubject());
                            return new AuthorizationDecision(allowed);
                        }))
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(accessTokenResolver)
                        .jwt(jwt -> jwt.decoder(accessJwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(this::writeAuthenticationError))
                .exceptionHandling(config -> config
                        .authenticationEntryPoint(this::writeAuthenticationError)
                        .accessDeniedHandler((request, response, error) -> {
                            RequestLoggingFilter.recordException(request, error);
                            writeError(request, response, 403,
                                    error instanceof org.springframework.security.web.csrf.CsrfException
                                            ? "INVALID_CSRF_TOKEN" : "FORBIDDEN",
                                    "요청 권한 또는 CSRF 확인값을 확인해주세요.");
                        }));
        http.addFilterAfter(new JwtUserExistenceAuthenticationFilter(jwtUserExistenceVerifier),
                org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        var chain = http.build();
        if (!csrfDisabled) {
            // Resource Server의 자동 CSRF 예외를 해제한다. JWT도 쿠키로 전달하므로 검증이 필요하다.
            for (var filter : chain.getFilters()) {
                if (filter instanceof org.springframework.security.web.csrf.CsrfFilter csrfFilter) {
                    csrfFilter.setRequireCsrfProtectionMatcher(
                            org.springframework.security.web.csrf.CsrfFilter.DEFAULT_CSRF_MATCHER);
                }
            }
        }
        return chain;
    }

    static boolean isInternalErrorEndpointDispatch(HttpServletRequest request) {
        return request.getDispatcherType() == DispatcherType.ERROR
                && (request.getContextPath() + "/error").equals(request.getRequestURI());
    }

    private org.springframework.core.convert.converter.Converter<Jwt, AbstractAuthenticationToken>
            jwtAuthenticationConverter() {
        var authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        return jwt -> {
            String subject = jwt.getSubject();
            if (subject == null || subject.isBlank()) {
                throw new InvalidBearerTokenException("JWT subject is missing");
            }
            // AI service JWT is a separate, explicitly authorized service principal, not a user row.
            boolean aiService = "AI".equals(jwt.getClaimAsString("actor")) && "ai-server".equals(subject);
            if (!aiService) {
                final long userId;
                try {
                    userId = Long.parseLong(subject);
                } catch (NumberFormatException error) {
                    throw new InvalidBearerTokenException("JWT subject is invalid", error);
                }
                if (userId <= 0) throw new InvalidBearerTokenException("JWT subject is invalid");
            }
            return new JwtAuthenticationToken(jwt, authoritiesConverter.convert(jwt), subject);
        };
    }

    private void writeAuthenticationError(jakarta.servlet.http.HttpServletRequest request,
            HttpServletResponse response, AuthenticationException error) throws java.io.IOException {
        RequestLoggingFilter.recordException(request, error);
        int status = HttpServletResponse.SC_UNAUTHORIZED;
        String code = "UNAUTHORIZED";
        String message = "로그인이 필요합니다.";
        String requestId = RequestLoggingFilter.requestId(request);
        if (response.isCommitted()) {
            log.warn("event=security_response_already_committed requestId={} dispatcherType={} intendedStatus={} actualStatus={} exceptionType={}",
                    requestId, request.getDispatcherType(), status, response.getStatus(), error.getClass().getSimpleName());
            return;
        }
        log.warn("event=security_rejected requestId={} dispatcherType={} status={} code={} exceptionType={} causeType={}",
                requestId, request.getDispatcherType(), status, code, error.getClass().getSimpleName(),
                error.getCause() == null ? "none" : error.getCause().getClass().getSimpleName());
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private void writeError(jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response,
            int status, String code, String message)
            throws java.io.IOException {
        log.warn("event=security_rejected requestId={} dispatcherType={} status={} code={}",
                RequestLoggingFilter.requestId(request), request.getDispatcherType(), status, code);
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

}
//
