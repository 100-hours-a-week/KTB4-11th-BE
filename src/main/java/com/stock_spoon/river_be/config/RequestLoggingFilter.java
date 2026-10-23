package com.stock_spoon.river_be.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** 요청과 DB 예외를 requestId로 추적한다. URL 원문, 쿼리, 헤더, 본문 및 일반 예외 메시지는 기록하지 않는다. */
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
            log.error("event=http_request_failed stage=request_processing method={} route={} causeType={} nestedCauseType={}",
                    request.getMethod(), routePattern(request), error.getClass().getSimpleName(),
                    error.getCause() == null ? "none" : error.getCause().getClass().getSimpleName());
            logSqlExceptionIfPresent(request, error);
            throw error;
        } finally {
            try {
                String pattern = routePattern(request);
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

    private static void logSqlExceptionIfPresent(HttpServletRequest request, Throwable error) {
        SQLException fallbackError =
                DbMigrationWriteFallbackClassifier.findWriteFallbackFailure(error);
        SQLException sqlError = fallbackError != null ? fallbackError : findSqlException(error);
        if (sqlError == null) return;

        String method = request.getMethod();
        String route = routePattern(request);
        boolean writeHttpMethod = DbMigrationWriteFallbackClassifier.isWriteHttpMethod(method);
        String sqlMessage = sanitizeMessage(sqlError.getMessage());

        log.error("event=database_exception_observed method={} route={} exceptionType={} sqlErrorCode={} sqlState={} sqlMessage={}",
                method, route, sqlError.getClass().getSimpleName(),
                sqlError.getErrorCode(), sqlError.getSQLState(), sqlMessage);

        if (fallbackError != null) {
            String signal = DbMigrationWriteFallbackClassifier.isMySqlCommunicationsLinkFailure(fallbackError)
                    ? "mysql_communications_link_failure"
                    : DbMigrationWriteFallbackClassifier.isMySqlReadOnlyFailure(fallbackError)
                            ? "mysql_read_only_server_1290"
                            : "proxysql_error_9001";
            log.error("event=database_fallback_signal_observed signal={} method={} route={} "
                            + "writeHttpMethod={} sqlErrorCode={} sqlState={}",
                    signal, method, route, writeHttpMethod,
                    fallbackError.getErrorCode(), fallbackError.getSQLState());
            if (fallbackError.getErrorCode()
                    == DbMigrationWriteFallbackClassifier.PROXYSQL_HOSTGROUP_CONNECT_TIMEOUT) {
                log.error("event=proxysql_connect_timeout_observed method={} route={} "
                                + "writeHttpMethod={} sqlErrorCode={} sqlState={}",
                        method, route, writeHttpMethod,
                        fallbackError.getErrorCode(), fallbackError.getSQLState());
            }
            if (writeHttpMethod) {
                log.error("event=db_migration_write_fallback_candidate_observed signal={} "
                                + "method={} route={} sqlErrorCode={} sqlState={} action=none",
                        signal, method, route, fallbackError.getErrorCode(), fallbackError.getSQLState());
            }
        }
    }

    private static SQLException findSqlException(Throwable error) {
        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        SQLException selected = null;
        int selectedScore = -1;
        pending.add(error);

        while (!pending.isEmpty()) {
            Throwable candidate = pending.removeFirst();
            if (!visited.add(candidate)) continue;
            if (candidate instanceof SQLException sqlError) {
                int score = (sqlError.getErrorCode() == 0 ? 0 : 1)
                        + (sqlError.getSQLState() == null ? 0 : 2)
                        + (sqlError.getMessage() == null ? 0 : 4);
                if (score > selectedScore) {
                    selected = sqlError;
                    selectedScore = score;
                }
                if (sqlError.getNextException() != null) pending.addLast(sqlError.getNextException());
            }
            if (candidate.getCause() != null) pending.addLast(candidate.getCause());
        }
        return selected;
    }

    private static String routePattern(HttpServletRequest request) {
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        // 매핑되지 않은 경로에는 임의의 민감정보가 있을 수 있어 원문을 출력하지 않는다.
        return route == null ? "unmapped" : route.toString();
    }

    private static String sanitizeMessage(String message) {
        if (message == null) return "none";
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return singleLine.length() <= 1000 ? singleLine : singleLine.substring(0, 1000);
    }
}
