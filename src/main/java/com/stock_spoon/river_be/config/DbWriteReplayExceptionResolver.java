package com.stock_spoon.river_be.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

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
        if (fallbackError == null || !isEnabledFallbackSignal(fallbackError)
                || !DbMigrationWriteFallbackClassifier.isWriteHttpMethod(request.getMethod())) {
            return null;
        }

        DbWriteReplayRequestContext.AcceptedReplay accepted =
                requestContext.acceptedReplay(fallbackError);
        if (accepted != null) return acceptedResponse(response, accepted);
        String blockReason = requestContext.enqueueBlockReason(fallbackError);
        if (blockReason != null) {
            String requestId = MDC.get("requestId");
            log.info("event=db_write_replay_skipped requestId={} reason={} sqlErrorCode={} sqlState={}",
                    requestId == null ? "none" : requestId, blockReason,
                    fallbackError.getErrorCode(), fallbackError.getSQLState());
            return null;
        }

        DbWriteReplayRequestContext.ReplaySnapshot snapshot = requestContext.snapshot(fallbackError);
        if (snapshot == null) {
            String requestId = MDC.get("requestId");
            log.info("event=db_write_replay_skipped requestId={} reason=snapshot_unavailable",
                    requestId == null ? "none" : requestId);
            return null;
        }

        try {
            String messageBody = objectMapper.writeValueAsString(snapshot.envelope());
            int bodyBytes = messageBody.getBytes(StandardCharsets.UTF_8).length;
            if (bodyBytes > MAX_SQS_MESSAGE_BYTES) {
                log.warn("event=db_write_replay_skipped requestId={} reason=message_too_large bodyBytes={}",
                        snapshot.httpRequestId(), bodyBytes);
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
            log.info("event=db_write_replay_queued requestId={} replayRequestId={} sqlErrorCode={} sqlState={}",
                    snapshot.httpRequestId(), snapshot.replayRequestId(),
                    fallbackError.getErrorCode(), fallbackError.getSQLState());
            return acceptedResponse(response, accepted);
        } catch (Exception enqueueFailure) {
            log.error("event=db_write_replay_enqueue_failed requestId={} sqlErrorCode={} sqlState={} failureType={} stage=replay_enqueue replayRequestId={} transactionId={} nestedCauseType={}",
                    snapshot.httpRequestId(), fallbackError.getErrorCode(), fallbackError.getSQLState(),
                    enqueueFailure.getClass().getSimpleName(), snapshot.replayRequestId(), snapshot.transactionId(),
                    enqueueFailure.getCause() == null ? "none" : enqueueFailure.getCause().getClass().getSimpleName());
            return null;
        }
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
            log.warn("event=db_write_replay_response_write_failed requestId={} failureType={} stage=accepted_response_write replayRequestId={} transactionId={}",
                    accepted.httpRequestId(), writeFailure.getClass().getSimpleName(),
                    accepted.replayRequestId(), accepted.transactionId());
        }
        return new ModelAndView();
    }

    private boolean isEnabledFallbackSignal(SQLException sqlError) {
        // 9001 remains configurable. ErrorCode 0 requires MySQL SQLState 08S01.
        return DbMigrationWriteFallbackClassifier.isMySqlCommunicationsLinkFailure(sqlError)
                || properties.getProxySqlErrorCodes().contains(sqlError.getErrorCode());
    }
}
