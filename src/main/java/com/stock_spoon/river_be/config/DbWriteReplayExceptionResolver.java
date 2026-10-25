package com.stock_spoon.river_be.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.transaction.TransactionException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

/** Publishes failed typed write commands or confirmed rolled-back SQL for a separate Lambda consumer. */
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
        if (!DbMigrationWriteFallbackClassifier.isWriteHttpMethod(request.getMethod())) return null;
        SQLException fallbackError =
                DbMigrationWriteFallbackClassifier.findWriteFallbackFailure(exception);
        if (fallbackError == null && mayUseObservedFailure(exception)) {
            // Rollback may hide the original error. An unrelated business/validation exception
            // or a new constraint violation must never inherit a previous database failure.
            fallbackError = requestContext.observedFallbackFailure();
        }
        if (fallbackError == null) {
            SQLException connectionFailure =
                    DbMigrationWriteFallbackClassifier.findReadRetryableConnectionFailure(exception);
            if (connectionFailure != null && requestContext.hasBusinessCommand()) {
                // Acquisition/commit failures may occur outside query execution. Preserve the
                // command; missing SQL instrumentation never grants permission to replay it.
                fallbackError = connectionFailure;
            } else {
                if (connectionFailure != null) {
                    log.warn("event=db_write_replay_skipped requestId={} reason=fallback_signal_not_observed "
                                    + "sqlErrorCode={} sqlState={} exceptionType={}",
                            requestId(), connectionFailure.getErrorCode(), connectionFailure.getSQLState(),
                            exception.getClass().getSimpleName());
                }
                return null;
            }
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

        DbWriteReplayRequestContext.ReplaySnapshot snapshot;
        if (requestContext.hasBusinessCommand()) {
            // A complete command also covers failures at SELECT FOR UPDATE before any DML.
            // Uncertain or partially committed workflows are preserved for reconciliation.
            snapshot = requestContext.snapshotBusinessCommand(fallbackError);
        } else {
            String blockReason = requestContext.enqueueBlockReason(fallbackError);
            if (blockReason != null) {
                logSkip(blockReason, fallbackError);
                return null;
            }
            snapshot = requestContext.snapshot(fallbackError);
        }
        if (snapshot == null) {
            logSkip("snapshot_unavailable", fallbackError);
            return null;
        }

        try {
            String messageBody = objectMapper.writeValueAsString(snapshot.envelope());
            String operation = String.valueOf(snapshot.envelope().get("operation"));
            String replaySafety = String.valueOf(snapshot.envelope().getOrDefault("replaySafety", "confirmed_rollback"));
            var diagnostics = requestContext.diagnostics(fallbackError);
            log.info("event=db_write_replay_candidate requestId={} operation={} transactionId={} "
                            + "replaySafety={} transactionOutcome={} rollbackConfirmed={}",
                    snapshot.httpRequestId(), operation, snapshot.transactionId(), replaySafety,
                    diagnostics.transactionOutcome(), diagnostics.rollbackConfirmed());
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
                            + "messageId={} operation={} sqlErrorCode={} sqlState={} replaySafety={}",
                    snapshot.httpRequestId(), snapshot.replayRequestId(),
                    snapshot.transactionId(), result.messageId(),
                    operation, fallbackError.getErrorCode(), fallbackError.getSQLState(), replaySafety);
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
                        + "rollbackConfirmed={} replayable={} capturedStatementCount={} "
                        + "rollbackAttempted={} untrackedWriteObserved={}",
                requestId(), reason, diagnostics.transactionId(), fallbackError.getErrorCode(),
                fallbackError.getSQLState(), diagnostics.dmlObserved(), diagnostics.transactionOutcome(),
                diagnostics.rollbackConfirmed(), diagnostics.replayable(), diagnostics.capturedStatementCount(),
                diagnostics.rollbackAttempted(), diagnostics.untrackedWriteObserved());
    }

    private static boolean mayUseObservedFailure(Exception failure) {
        if (DbMigrationWriteFallbackClassifier.findReadRetryableConnectionFailure(failure) != null) return true;
        return (containsFailure(failure, DataAccessException.class) || containsFailure(failure, TransactionException.class))
                && !containsFailure(failure, SQLException.class);
    }

    private static boolean containsFailure(Throwable failure, Class<? extends Throwable> type) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable candidate = pending.removeFirst();
            if (!visited.add(candidate)) continue;
            if (type.isInstance(candidate)) return true;
            if (candidate.getCause() != null) pending.add(candidate.getCause());
            for (Throwable suppressed : candidate.getSuppressed()) pending.add(suppressed);
        }
        return false;
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
        body.putAll(requestContext.acceptedCommandMetadata());
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
                || properties.getProxySqlErrorCodes().contains(sqlError.getErrorCode())
                || (requestContext.hasBusinessCommand()
                        && sqlError.getErrorCode() != DbMigrationWriteFallbackClassifier.PROXYSQL_HOSTGROUP_CONNECT_TIMEOUT
                        && DbMigrationWriteFallbackClassifier.isReadRetryableConnectionFailure(sqlError));
    }
}
