package com.stock_spoon.river_be.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import static org.assertj.core.api.Assertions.*;

class RequestLoggingFilterTests {
    @Test
    void correlatesRequestAndRestoresContextWithoutLoggingSecrets() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        MDC.put("requestId", "outer");
        try {
            var request = new MockHttpServletRequest("POST", "/api/private-secret");
            request.setQueryString("token=query-secret");
            request.addHeader("Authorization", "Bearer header-secret");
            request.addHeader("X-Request-Id", "untrusted-id");
            request.setContent("body-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var response = new MockHttpServletResponse();
            new RequestLoggingFilter().doFilter(request, response, (req, res) -> {
                assertThat(MDC.get("requestId")).isEqualTo(response.getHeader("X-Request-Id"));
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/{id}");
                response.setStatus(503);
            });
            assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f-]{36}");
            assertThat(MDC.get("requestId")).isEqualTo("outer");
            assertThat(logs.list).anySatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("event=http_response_audit", "route=/api/{id}",
                        "dispatcherType=REQUEST", "status=503", "elapsedMs=");
            });
            assertThat(logs.list.toString()).doesNotContain("private-secret", "query-secret", "header-secret", "body-secret", "untrusted-id");
        } finally {
            MDC.remove("requestId");
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void preservesExceptionAndClearsContextOnFailure() {
        var error = new ServletException("secret-error-message");
        var request = new MockHttpServletRequest("GET", "/secret");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> new RequestLoggingFilter().doFilter(request, response,
                (req, res) -> { throw error; })).isSameAs(error);
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void nestedErrorDispatchAuditsFinalStatusAndExceptionOnce() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        var request = new MockHttpServletRequest("POST", "/api/write");
        var response = new MockHttpServletResponse();
        var filter = new RequestLoggingFilter();
        try {
            filter.doFilter(request, response, (req, res) -> {
                req.setAttribute(RequestDispatcher.ERROR_EXCEPTION,
                        new IllegalStateException("handler failed", new java.sql.SQLTransientConnectionException("db down")));
                ((MockHttpServletRequest) req).setDispatcherType(DispatcherType.ERROR);
                var errorResponse = (MockHttpServletResponse) res;
                filter.doFilter(req, res, (errorRequest, ignored) -> errorResponse.setStatus(503));
            });

            var audits = logs.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("event=http_response_audit"))
                    .toList();
            assertThat(audits).hasSize(1);
            assertThat(audits.get(0).getFormattedMessage()).contains(
                    "dispatcherType=ERROR", "status=503", "exceptionType=IllegalStateException",
                    "exceptionCauseType=SQLTransientConnectionException");
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void logsSqlErrorDetailsWithRequestIdWithoutLoggingOuterExceptionMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        var sqlError = new SQLException("read only denied", "HY000", 1290);
        var error = new RuntimeException("outer exception secret", sqlError);
        var request = new MockHttpServletRequest("POST", "/api/orders/secret-id");
        var response = new MockHttpServletResponse();

        try {
            assertThatThrownBy(() -> new RequestLoggingFilter().doFilter(request, response, (req, res) -> {
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/orders/{orderId}");
                throw error;
            })).isSameAs(error);

            assertThat(logs.list).anySatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains(
                        "event=database_exception_observed", "route=/api/orders/{orderId}",
                        "exceptionType=SQLException", "sqlErrorCode=1290", "sqlState=HY000",
                        "sqlMessage=read only denied");
                assertThat(event.getMDCPropertyMap()).containsKey("requestId");
            });
            assertThat(logs.list.toString()).doesNotContain("outer exception secret", "secret-id");
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void marksNestedProxySql9001AsFallbackCandidateForWriteHttpMethod() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        var poolTimeout = new SQLTransientConnectionException("Hikari pool connection timeout", "08001");
        poolTimeout.setNextException(new SQLException("ProxySQL hostgroup timeout", "HY000", 9001));
        var error = new RuntimeException("request failed", poolTimeout);
        var request = new MockHttpServletRequest("POST", "/api/orders");
        var response = new MockHttpServletResponse();

        try {
            assertThatThrownBy(() -> new RequestLoggingFilter().doFilter(request, response,
                    (req, res) -> { throw error; })).isSameAs(error);

            assertThat(logs.list).anySatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains(
                            "event=db_migration_write_fallback_candidate_observed",
                            "signal=proxysql_error_9001", "method=POST",
                            "sqlErrorCode=9001", "sqlState=HY000", "action=none"));
            assertThat(logs.list).anySatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains(
                            "event=proxysql_connect_timeout_observed", "writeHttpMethod=true"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void doesNotMarkReadHttpMethodAsWriteFallbackCandidate() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        var request = new MockHttpServletRequest("GET", "/api/orders");
        var response = new MockHttpServletResponse();

        try {
            assertThatThrownBy(() -> new RequestLoggingFilter().doFilter(request, response,
                    (req, res) -> { throw new RuntimeException(new SQLException("ProxySQL hostgroup timeout", "HY000", 9001)); }))
                    .isInstanceOf(RuntimeException.class);

            assertThat(logs.list).noneSatisfy(event ->
                    assertThat(event.getFormattedMessage())
                            .contains("event=db_migration_write_fallback_candidate_observed"));
            assertThat(logs.list).anySatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains(
                            "event=proxysql_connect_timeout_observed", "writeHttpMethod=false"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void doesNotMarkOtherSqlErrorCodesAsProxySqlFallbackCandidates() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        var request = new MockHttpServletRequest("POST", "/api/orders");
        var response = new MockHttpServletResponse();

        try {
            assertThatThrownBy(() -> new RequestLoggingFilter().doFilter(request, response,
                    (req, res) -> { throw new RuntimeException(new SQLException("read only denied", "HY000", 1290)); }))
                    .isInstanceOf(RuntimeException.class);

            assertThat(logs.list).noneSatisfy(event ->
                    assertThat(event.getFormattedMessage())
                            .contains("event=db_migration_write_fallback_candidate_observed"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
