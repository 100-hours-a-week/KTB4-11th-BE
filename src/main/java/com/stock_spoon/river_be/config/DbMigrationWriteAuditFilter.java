package com.stock_spoon.river_be.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Carries a bounded load-test write identifier into transaction outcome logs.
 * The identifier is only a correlation label; the server-generated requestId remains authoritative.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class DbMigrationWriteAuditFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-DB-Migration-Write-Id";
    public static final String MDC_KEY = "dbMigrationWriteId";

    private static final Pattern VALID_WRITE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}");
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String writeId = request.getHeader(HEADER_NAME);
        if (writeId == null || !WRITE_METHODS.contains(request.getMethod())
                || !VALID_WRITE_ID.matcher(writeId).matches()) {
            chain.doFilter(request, response);
            return;
        }

        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, writeId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) MDC.remove(MDC_KEY);
            else MDC.put(MDC_KEY, previous);
        }
    }
}
