package com.stock_spoon.river_be.config;

import com.stock_spoon.river_be.auth.service.JwtUserExistenceVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Checks that a validated end-user JWT still identifies a persisted user. */
final class JwtUserExistenceAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtUserExistenceAuthenticationFilter.class);
    private final JwtUserExistenceVerifier verifier;

    JwtUserExistenceAuthenticationFilter(JwtUserExistenceVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            chain.doFilter(request, response);
            return;
        }

        var jwt = jwtAuthentication.getToken();
        String subject = jwt.getSubject();
        boolean aiService = "AI".equals(jwt.getClaimAsString("actor")) && "ai-server".equals(subject);
        if (aiService) {
            chain.doFilter(request, response);
            return;
        }

        long userId;
        try {
            userId = Long.parseLong(subject);
        } catch (RuntimeException invalidSubject) {
            reject(request, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
                    "로그인이 필요합니다.", "InvalidBearerTokenException");
            return;
        }

        try {
            if (!verifier.exists(userId)) {
                SecurityContextHolder.clearContext();
                reject(request, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
                        "로그인이 필요합니다.", "UserNotFound");
                return;
            }
        } catch (AuthenticationServiceException unavailable) {
            request.setAttribute(RequestLoggingFilter.EXCEPTION_ATTRIBUTE,
                    AuthenticationServiceException.class.getSimpleName());
            reject(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "AUTHENTICATION_DATABASE_UNAVAILABLE",
                    "인증 확인을 일시적으로 처리할 수 없습니다.",
                    AuthenticationServiceException.class.getSimpleName());
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response,
            int status, String code, String message, String exceptionType) throws IOException {
        RequestLoggingFilter.recordException(request, exceptionType, null);
        String requestId = RequestLoggingFilter.requestId(request);
        if (response.isCommitted()) {
            log.warn("event=authentication_response_already_committed requestId={} dispatcherType={} intendedStatus={} actualStatus={} exceptionType={}",
                    requestId, request.getDispatcherType(), status, response.getStatus(), exceptionType);
            return;
        }
        log.warn("event=authentication_rejected requestId={} dispatcherType={} status={} code={} exceptionType={}",
                requestId, request.getDispatcherType(), status, code, exceptionType);
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
