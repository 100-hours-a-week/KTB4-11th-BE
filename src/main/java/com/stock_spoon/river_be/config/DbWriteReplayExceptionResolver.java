package com.stock_spoon.river_be.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.method.HandlerMethod;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

/** Enqueues a confirmed rolled-back SQL write before the normal MVC error handlers run. */
public class DbWriteReplayExceptionResolver implements HandlerExceptionResolver {
    private static final Logger log = LoggerFactory.getLogger(DbWriteReplayExceptionResolver.class);
    private static final int MAX_SQS_MESSAGE_BYTES = 262_144;
    private static final String MESSAGE_GROUP_ID = "db-write-replay";

    private final DbWriteReplayProperties properties;
    private final DbWriteReplayRequestContext requestContext;
    private final SqsClient sqsClient;
    private final JsonMapper objectMapper;

    public DbWriteReplayExceptionResolver(
            DbWriteReplayProperties properties,
            DbWriteReplayRequestContext requestContext,
            SqsClient sqsClient,
            JsonMapper objectMapper) {
        this.properties = properties;
        this.requestContext = requestContext;
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        SQLException fallbackError =
                DbMigrationWriteFallbackClassifier.findWriteFallbackFailure(exception);
        if (fallbackError == null) {
            // Rollback failures can replace the original JDBC exception in Spring's outer exception.
            // Reuse the exact SQLException captured by the JDBC listener for this request.
            fallbackError = requestContext.observedFallbackFailure();
        }
        if (fallbackError == null) {
            SQLException connectionFailure =
                    DbMigrationWriteFallbackClassifier.findReadRetryableConnectionFailure(exception);
            if (connectionFailure != null) {
                log.warn("event=db_write_replay_skipped requestId={} reason=fallback_signal_not_observed "
                                + "sqlErrorCode={} sqlState={} exceptionType={}",
                        requestId(), connectionFailure.getErrorCode(), connectionFailure.getSQLState(),
                        exception.getClass().getSimpleName());
            }
            return null;
        }
        if (!isEnabledFallbackSignal(fallbackError)) {
            log.info("event=db_write_replay_skipped requestId={} reason=fallback_signal_not_enabled "
                            + "sqlErrorCode={} sqlState={}",
                    requestId(), fallbackError.getErrorCode(), fallbackError.getSQLState());
            return null;
        }

        DbWriteReplayRequestContext.AcceptedReplay accepted =
                requestContext.acceptedReplay(fallbackError);
        if (accepted != null) return acceptedResponse(response, accepted);

        String blockReason = requestContext.enqueueBlockReason(fallbackError);
        boolean businessCommandFallback = false;
        Map<String, Object> businessCommand = null;
        if ("no_dml_before_fallback".equals(blockReason) && isBusinessCommandHandler(handler)) {
            businessCommand = captureBusinessCommand(request);
            if (businessCommand == null) {
                blockReason = "business_command_capture_unavailable";
            } else {
                blockReason = requestContext.enqueueBlockReason(fallbackError, true);
                businessCommandFallback = blockReason == null;
            }
        }
        if (blockReason != null) {
            logSkip(blockReason, fallbackError);
            return null;
        }

        DbWriteReplayRequestContext.ReplaySnapshot snapshot = businessCommandFallback
                ? requestContext.snapshotBusinessCommand(fallbackError, businessCommand)
                : requestContext.snapshot(fallbackError);
        if (snapshot == null) {
            logSkip("snapshot_unavailable", fallbackError);
            return null;
        }

        try {
            String messageBody = objectMapper.writeValueAsString(snapshot.envelope());
            String operation = String.valueOf(snapshot.envelope().get("operation"));
            log.info("event=db_write_replay_candidate requestId={} operation={} transactionId={}",
                    snapshot.httpRequestId(), operation, snapshot.transactionId());
            int bodyBytes = messageBody.getBytes(StandardCharsets.UTF_8).length;
            if (bodyBytes > MAX_SQS_MESSAGE_BYTES) {
                log.warn("event=db_write_replay_skipped requestId={} reason=message_too_large "
                                + "operation={} transactionId={} bodyBytes={}",
                        snapshot.httpRequestId(), operation, snapshot.transactionId(), bodyBytes);
                return null;
            }

            var result = sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(properties.getQueueUrl())
                    .messageBody(messageBody)
                    .messageGroupId(MESSAGE_GROUP_ID)
                    .messageDeduplicationId(snapshot.replayRequestId())
                    .build());

            accepted = new DbWriteReplayRequestContext.AcceptedReplay(
                    snapshot.replayRequestId(), snapshot.transactionId(),
                    snapshot.httpRequestId(), result.messageId());
            requestContext.markEnqueued(fallbackError, accepted);
            log.info("event=db_write_replay_queued requestId={} replayRequestId={} transactionId={} "
                            + "messageId={} operation={} sqlErrorCode={} sqlState={}",
                    snapshot.httpRequestId(), snapshot.replayRequestId(),
                    snapshot.transactionId(), result.messageId(),
                    operation, fallbackError.getErrorCode(), fallbackError.getSQLState());
            return acceptedResponse(response, accepted);
        } catch (Exception enqueueFailure) {
            log.error("event=db_write_replay_enqueue_failed requestId={} transactionId={} operation={} "
                            + "sqlErrorCode={} sqlState={} failureType={} serviceErrorCode={} "
                            + "httpStatusCode={} awsRequestId={}",
                    snapshot.httpRequestId(), snapshot.transactionId(),
                    String.valueOf(snapshot.envelope().get("operation")),
                    fallbackError.getErrorCode(), fallbackError.getSQLState(),
                    enqueueFailure.getClass().getSimpleName(),
                    serviceErrorCode(enqueueFailure), httpStatusCode(enqueueFailure),
                    awsRequestId(enqueueFailure));
            return null;
        }
    }

    private void logSkip(String reason, SQLException fallbackError) {
        DbWriteReplayRequestContext.ReplayDiagnostics diagnostics =
                requestContext.diagnostics(fallbackError);
        log.info("event=db_write_replay_skipped requestId={} reason={} transactionId={} "
                        + "sqlErrorCode={} sqlState={} dmlObserved={} transactionOutcome={} "
                        + "rollbackConfirmed={} replayable={} capturedStatementCount={}",
                requestId(), reason, diagnostics.transactionId(), fallbackError.getErrorCode(),
                fallbackError.getSQLState(), diagnostics.dmlObserved(), diagnostics.transactionOutcome(),
                diagnostics.rollbackConfirmed(), diagnostics.replayable(), diagnostics.capturedStatementCount());
    }

    private static boolean isBusinessCommandHandler(Object handler) {
        return handler instanceof HandlerMethod handlerMethod
                && handlerMethod.hasMethodAnnotation(DbWriteReplayBusinessCommand.class);
    }

    /**
     * Captures only explicitly annotated write endpoints. Credentials and arbitrary headers are
     * excluded; the request wrapper bounds body capture before it reaches SQS.
     */
    private static Map<String, Object> captureBusinessCommand(HttpServletRequest request) {
        if (!(request instanceof DbWriteReplayHttpRequestWrapper wrapped)
                || wrapped.contentOverflowed()) {
            return null;
        }

        byte[] body = wrapped.getContentAsByteArray();
        if (body.length == 0 && request.getContentLengthLong() > 0) return null;
        String contentType = request.getContentType();
        if (body.length > 0 && (contentType == null
                || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json"))) {
            return null;
        }

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("httpMethod", request.getMethod());
        command.put("path", request.getRequestURI());
        if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
            command.put("queryString", request.getQueryString());
        }
        if (contentType != null) command.put("contentType", contentType);
        java.security.Principal principal = request.getUserPrincipal();
        if (principal != null && principal.getName() != null) {
            command.put("actorId", principal.getName());
        }
        command.put("body", body.length == 0 ? "" : new String(body, StandardCharsets.UTF_8));
        return command;
    }

    private static String requestId() {
        String requestId = MDC.get("requestId");
        return requestId == null ? "none" : requestId;
    }

    private static String serviceErrorCode(Exception failure) {
        if (failure instanceof SqsException sqsFailure && sqsFailure.awsErrorDetails() != null) {
            return safeLogValue(sqsFailure.awsErrorDetails().errorCode());
        }
        return "none";
    }

    private static String httpStatusCode(Exception failure) {
        if (failure instanceof SqsException sqsFailure && sqsFailure.statusCode() > 0) {
            return Integer.toString(sqsFailure.statusCode());
        }
        return "none";
    }

    private static String awsRequestId(Exception failure) {
        if (failure instanceof SqsException sqsFailure && sqsFailure.requestId() != null) {
            return safeLogValue(sqsFailure.requestId());
        }
        return "none";
    }

    private static String safeLogValue(String value) {
        if (value == null || value.isBlank()) return "none";
        return value.replace('\n', '_').replace('\r', '_').replace(' ', '_');
    }

    private ModelAndView acceptedResponse(
            HttpServletResponse response,
            DbWriteReplayRequestContext.AcceptedReplay accepted) {
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "QUEUED");
        body.put("requestId", accepted.httpRequestId());
        body.put("replayRequestId", accepted.replayRequestId());
        try {
            objectMapper.writeValue(response.getOutputStream(), body);
        } catch (IOException writeFailure) {
            log.warn("event=db_write_replay_response_write_failed requestId={} failureType={}",
                    accepted.httpRequestId(), writeFailure.getClass().getSimpleName());
        }
        return new ModelAndView();
    }

    private boolean isEnabledFallbackSignal(SQLException sqlError) {
        // 9001 remains configurable. ErrorCode 0 requires MySQL SQLState 08S01.
        return DbMigrationWriteFallbackClassifier.isMySqlCommunicationsLinkFailure(sqlError)
                || properties.getProxySqlErrorCodes().contains(sqlError.getErrorCode());
    }
}
