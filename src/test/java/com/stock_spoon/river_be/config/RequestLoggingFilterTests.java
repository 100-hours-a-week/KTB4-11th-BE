package com.stock_spoon.river_be.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
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
                assertThat(event.getFormattedMessage()).contains("event=http_request", "route=/api/{id}", "status=503", "elapsedMs=");
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
}
