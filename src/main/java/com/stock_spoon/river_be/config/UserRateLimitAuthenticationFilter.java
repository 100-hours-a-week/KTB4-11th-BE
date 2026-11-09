package com.stock_spoon.river_be.config;

import com.stock_spoon.river_be.config.UserRateLimitService.Decision;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies the Valkey limit to signed-in end users before the database user-existence lookup. */
final class UserRateLimitAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(UserRateLimitAuthenticationFilter.class);
    private final UserRateLimitService rateLimitService;

    UserRateLimitAuthenticationFilter(UserRateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getDispatcherType() != DispatcherType.REQUEST
                || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !request.getServletPath().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !"USER".equals(jwtAuthentication.getToken().getClaimAsString("actor"))) {
            chain.doFilter(request, response);
            return;
        }

        final long userId;
        try {
            userId = Long.parseLong(jwtAuthentication.getToken().getSubject());
            if (userId <= 0) {
                chain.doFilter(request, response);
                return;
            }
        } catch (RuntimeException invalidSubject) {
            chain.doFilter(request, response);
            return;
        }

        final Decision decision;
        try {
            decision = rateLimitService.check(userId);
        } catch (RuntimeException unavailable) {
            log.error("event=user_rate_limit_unavailable requestId={} failOpen=true causeType={}",
                    RequestLoggingFilter.requestId(request), unavailable.getClass().getSimpleName());
            chain.doFilter(request, response);
            return;
        }

        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해주세요.\"}");
    }
}
