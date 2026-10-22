package com.stock_spoon.river_be.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerMapping;
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
        SQLException proxySqlError =
                DbMigrationWriteFallbackClassifier.findProxySqlConnectTimeout(exception);
        if (proxySqlError == null
                || !properties.getProxySqlErrorCodes().contains(proxySqlError.getErrorCode())) {
            return null;
        }
        if (!isKnownSingleDmlRoute(request)) return null;

        DbWriteReplayRequestContext.AcceptedReplay accepted =
                requestContext.acceptedReplay(proxySqlError);
        if (accepted != null) return acceptedResponse(response, accepted);
        if (!requestContext.canEnqueue(proxySqlError)) return null;

        DbWriteReplayRequestContext.ReplaySnapshot snapshot = requestContext.snapshot(proxySqlError);
        if (snapshot == null) return null;

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
            requestContext.markEnqueued(proxySqlError, accepted);
            log.info("event=db_write_replay_queued requestId={} replayRequestId={} sqlErrorCode={} statementCount=1",
                    snapshot.httpRequestId(), snapshot.replayRequestId(), proxySqlError.getErrorCode());
            return acceptedResponse(response, accepted);
        } catch (Exception enqueueFailure) {
            log.error("event=db_write_replay_enqueue_failed requestId={} sqlErrorCode={} failureType={}",
                    snapshot.httpRequestId(), proxySqlError.getErrorCode(),
                    enqueueFailure.getClass().getSimpleName());
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
            log.warn("event=db_write_replay_response_write_failed requestId={} failureType={}",
                    accepted.httpRequestId(), writeFailure.getClass().getSimpleName());
        }
        return new ModelAndView();
    }

    /**
     * The current SQL capture can only replay statements that reached JDBC. Keep it to
     * handlers whose transaction is known to contain one DML statement; later Hibernate
     * actions may not have reached JDBC after an earlier statement failed.
     */
    private static boolean isKnownSingleDmlRoute(HttpServletRequest request) {
        Object matchedPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(matchedPattern instanceof String route)) return false;
        return ("POST".equals(request.getMethod())
                        && "/api/v1/users/me/accounts".equals(route))
                || ("PATCH".equals(request.getMethod())
                        && "/api/v1/users/me/accounts/{accountId}".equals(route));
    }
}
