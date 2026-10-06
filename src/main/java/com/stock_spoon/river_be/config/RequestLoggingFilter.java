package com.stock_spoon.river_be.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** 인증 거절도 추적한다. URL 원문, 쿼리, 헤더, 본문 및 예외 메시지는 기록하지 않는다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String previous = MDC.get("requestId");
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        boolean failed = false;
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        try {
            log.debug("event=http_request_started method={}", request.getMethod());
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException error) {
            failed = true;
            log.error("event=http_request_failed causeType={}", error.getClass().getSimpleName());
            throw error;
        } finally {
            try {
                Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                // 매핑되지 않은 경로에는 임의의 민감정보가 있을 수 있어 원문을 출력하지 않는다.
                String pattern = route == null ? "unmapped" : route.toString();
                int status = failed ? 500 : response.getStatus();
                var level = status >= 500 ? log.atError() : status >= 400 ? log.atWarn() : log.atInfo();
                level.log("event=http_request method={} route={} status={} elapsedMs={}",
                        request.getMethod(), pattern, status, (System.nanoTime() - started) / 1_000_000);
            } finally {
                if (previous == null) MDC.remove("requestId");
                else MDC.put("requestId", previous);
            }
        }
    }
}
