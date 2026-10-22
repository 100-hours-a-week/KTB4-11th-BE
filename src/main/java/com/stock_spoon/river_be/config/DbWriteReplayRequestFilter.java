package com.stock_spoon.river_be.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** Keeps the replay capture scoped to one synchronous HTTP request. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@ConditionalOnProperty(prefix = "db-write-replay", name = "enabled", havingValue = "true")
public class DbWriteReplayRequestFilter extends OncePerRequestFilter {
    private final DbWriteReplayRequestContext requestContext;

    public DbWriteReplayRequestFilter(DbWriteReplayRequestContext requestContext) {
        this.requestContext = requestContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String requestId = MDC.get("requestId");
        if (!StringUtils.hasText(requestId)) requestId = UUID.randomUUID().toString();
        requestContext.beginRequest(requestId, MDC.get(DbMigrationWriteAuditFilter.MDC_KEY));
        try {
            chain.doFilter(request, response);
        } finally {
            requestContext.endRequest();
        }
    }
}
