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
import jakarta.servlet.DispatcherType;
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
    private static final String REQUEST_ID_ATTRIBUTE = RequestLoggingFilter.class.getName() + ".requestId";
    private static final String STARTED_ATTRIBUTE = RequestLoggingFilter.class.getName() + ".startedNanos";
    static final String EXCEPTION_ATTRIBUTE = RequestLoggingFilter.class.getName() + ".exceptionType";
    static final String EXCEPTION_CAUSE_ATTRIBUTE = RequestLoggingFilter.class.getName() + ".exceptionCauseType";
    private static final String ERROR_DISPATCH_ATTRIBUTE = RequestLoggingFilter.class.getName() + ".errorDispatchSeen";
    private static final String RESPONSE_AUDIT_ATTRIBUTE = RequestLoggingFilter.class.getName() + ".responseAudited";

    static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return value instanceof String requestId ? requestId : "missing";
    }

    static void recordException(HttpServletRequest request, Throwable error) {
        if (error == null) return;
        request.setAttribute(EXCEPTION_ATTRIBUTE, error.getClass().getSimpleName());
        if (error.getCause() != null) {
            request.setAttribute(EXCEPTION_CAUSE_ATTRIBUTE, error.getCause().getClass().getSimpleName());
        }
    }

    static void recordException(HttpServletRequest request, String exceptionType, String causeType) {
        if (exceptionType != null && !exceptionType.isBlank()) {
            request.setAttribute(EXCEPTION_ATTRIBUTE, exceptionType);
        }
        if (causeType != null && !causeType.isBlank()) {
            request.setAttribute(EXCEPTION_CAUSE_ATTRIBUTE, causeType);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String previous = MDC.get("requestId");
        String previousDispatcherType = MDC.get("dispatcherType");
        String requestId = (String) request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
            request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
            request.setAttribute(STARTED_ATTRIBUTE, System.nanoTime());
        }
        Object startedValue = request.getAttribute(STARTED_ATTRIBUTE);
        long started = startedValue instanceof Long value ? value : System.nanoTime();
        boolean failed = false;
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            request.setAttribute(ERROR_DISPATCH_ATTRIBUTE, Boolean.TRUE);
            recordException(request, (Throwable) request.getAttribute(jakarta.servlet.RequestDispatcher.ERROR_EXCEPTION));
        }
        MDC.put("requestId", requestId);
        MDC.put("dispatcherType", request.getDispatcherType().name());
        if (!response.containsHeader("X-Request-Id")) response.setHeader("X-Request-Id", requestId);
        try {
            log.debug("event=http_request_started method={}", request.getMethod());
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException error) {
            failed = true;
            recordException(request, error);
            log.error("event=http_request_failed requestId={} dispatcherType={} exceptionType={} causeType={}",
                    requestId, request.getDispatcherType(), error.getClass().getSimpleName(),
                    error.getCause() == null ? "none" : error.getCause().getClass().getSimpleName());
            logSqlExceptionIfPresent(request, error);
            throw error;
        } finally {
            try {
                // An exception escaping REQUEST is finalized by the container's ERROR dispatch.
                // Never guess 500 here; record that final dispatch when it occurs.
                if (!failed || request.getDispatcherType() == DispatcherType.ERROR) {
                    auditResponse(request, response, started, requestId);
                }
            } finally {
                if (previous == null) MDC.remove("requestId");
                else MDC.put("requestId", previous);
                if (previousDispatcherType == null) MDC.remove("dispatcherType");
                else MDC.put("dispatcherType", previousDispatcherType);
            }
        }
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        request.setAttribute(ERROR_DISPATCH_ATTRIBUTE, Boolean.TRUE);
        recordException(request, (Throwable) request.getAttribute(jakarta.servlet.RequestDispatcher.ERROR_EXCEPTION));
        String requestId = requestId(request);
        String previous = MDC.get("requestId");
        String previousDispatcherType = MDC.get("dispatcherType");
        Object startedValue = request.getAttribute(STARTED_ATTRIBUTE);
        long started = startedValue instanceof Long value ? value : System.nanoTime();
        if (previous == null) MDC.put("requestId", requestId);
        MDC.put("dispatcherType", DispatcherType.ERROR.name());
        try {
            chain.doFilter(request, response);
        } finally {
            auditResponse(request, response, started, requestId);
            if (previous == null) MDC.remove("requestId");
            else MDC.put("requestId", previous);
            if (previousDispatcherType == null) MDC.remove("dispatcherType");
            else MDC.put("dispatcherType", previousDispatcherType);
        }
    }

    private static void auditResponse(HttpServletRequest request, HttpServletResponse response,
            long started, String requestId) {
        if (Boolean.TRUE.equals(request.getAttribute(RESPONSE_AUDIT_ATTRIBUTE))) return;
        request.setAttribute(RESPONSE_AUDIT_ATTRIBUTE, Boolean.TRUE);
        String pattern = routePattern(request);
        int status = response.getStatus();
        String dispatcher = Boolean.TRUE.equals(request.getAttribute(ERROR_DISPATCH_ATTRIBUTE))
                || request.getDispatcherType() == DispatcherType.ERROR
                ? DispatcherType.ERROR.name() : request.getDispatcherType().name();
        Object exception = request.getAttribute(EXCEPTION_ATTRIBUTE);
        String exceptionType = exception == null ? "none" : exception.toString();
        Object cause = request.getAttribute(EXCEPTION_CAUSE_ATTRIBUTE);
        String causeType = cause == null ? "none" : cause.toString();
        var level = status >= 500 ? log.atError() : status >= 400 ? log.atWarn() : log.atInfo();
        level.log("event=http_response_audit requestId={} dispatcherType={} method={} route={} status={} exceptionType={} exceptionCauseType={} elapsedMs={}",
                requestId, dispatcher, request.getMethod(), pattern, status, exceptionType, causeType,
                (System.nanoTime() - started) / 1_000_000);
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
