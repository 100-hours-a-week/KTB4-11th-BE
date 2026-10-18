package com.stock_spoon.river_be.dbwrite;

import com.stock_spoon.river_be.account.dto.AccountCreateRequest;
import com.stock_spoon.river_be.account.dto.AccountNameUpdateRequest;
import com.stock_spoon.river_be.account.dto.OnboardingRequest;
import com.stock_spoon.river_be.competition.dto.CompetitionCreateRequest;
import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.entity.Order;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** Stores replayable business commands, never raw SQL. */
@Service
public class DbWriteReplayService {
    private static final Logger log = LoggerFactory.getLogger(DbWriteReplayService.class);
    private static final String GLOBAL_MESSAGE_GROUP_ID = "db-write-replay-global";
    private static final int MAX_MESSAGE_BYTES = 1_048_576;
    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private final SqsClient sqs;
    private final boolean enabled;
    private final String queueUrl;
    private final Set<Integer> proxySqlErrorCodes;

    public DbWriteReplayService(SqsClient sqs,
            @Value("${db.write-replay.enabled:false}") boolean enabled,
            @Value("${db.write-replay.queue-url:}") String queueUrl,
            @Value("${db.write-replay.proxysql-error-codes:}") String proxySqlErrorCodes) {
        this.sqs = sqs;
        this.enabled = enabled;
        this.queueUrl = queueUrl;
        this.proxySqlErrorCodes = parseProxySqlErrorCodes(proxySqlErrorCodes);
        if (enabled && (queueUrl == null || queueUrl.isBlank())) {
            throw new IllegalStateException("DB_WRITE_REPLAY_ENABLED=true requires DB_WRITE_REPLAY_QUEUE_URL");
        }
    }

    public Optional<String> enqueueIfWriterUnavailable(Throwable failure, Operation operation, Object payload) {
        boolean classifiedAsWriterUnavailable = SqlWriteFailureClassifier.isWriterUnavailable(failure, proxySqlErrorCodes);
        logObservedFailure(failure, operation, classifiedAsWriterUnavailable);
        if (!enabled || !classifiedAsWriterUnavailable) return Optional.empty();

        String requestId = UUID.randomUUID().toString();
        var envelope = new MessageEnvelope(1, requestId, operation.name(), Instant.now().toString(), payload);
        try {
            String body = JSON.writeValueAsString(envelope);
            if (body.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
                throw new IllegalArgumentException("DB write replay message exceeds the SQS 1 MiB limit");
            }
            var response = sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .messageGroupId(GLOBAL_MESSAGE_GROUP_ID)
                    .messageDeduplicationId(requestId)
                    .build());
            log.warn("event=db_write_replay_queued operation={} requestId={} messageId={}",
                    operation, requestId, response.messageId());
            return Optional.of(requestId);
        } catch (Exception error) {
            throw new DbWriteReplayEnqueueException(error);
        }
    }

    private static Set<Integer> parseProxySqlErrorCodes(String configured) {
        if (configured == null || configured.isBlank()) return Set.of();
        try {
            return java.util.Arrays.stream(configured.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(Integer::parseInt)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("DB_WRITE_REPLAY_PROXYSQL_ERROR_CODES must be comma-separated integers", error);
        }
    }

    private void logObservedFailure(Throwable failure, Operation operation, boolean classified) {
        String requestId = MDC.get("requestId");
        var sqlErrors = findSqlExceptions(failure);
        if (sqlErrors.isEmpty()) return;
        log.warn("event=db_write_failure_observed operation={} requestId={} classifiedAsWriterUnavailable={} "
                        + "replayEnabled={} configuredProxySqlErrorCodes={} sqlExceptionCount={} causeType={}",
                operation, requestId == null ? "none" : requestId, classified,
                enabled, proxySqlErrorCodes, sqlErrors.size(),
                failure == null ? "none" : failure.getClass().getSimpleName());
        for (SQLException sqlError : sqlErrors) {
            log.warn("event=db_write_sql_exception operation={} requestId={} exceptionType={} vendorCode={} "
                            + "sqlState={} message={}",
                    operation, requestId == null ? "none" : requestId,
                    sqlError.getClass().getSimpleName(), sqlError.getErrorCode(), sqlError.getSQLState(),
                    logSafeMessage(sqlError.getMessage()));
        }
    }

    private static java.util.List<SQLException> findSqlExceptions(Throwable failure) {
        if (failure == null) return java.util.List.of();
        var pending = new ArrayDeque<Throwable>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var errors = new java.util.ArrayList<SQLException>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (current instanceof SQLException sql) {
                errors.add(sql);
                if (sql.getNextException() != null) pending.addLast(sql.getNextException());
            }
            if (current.getCause() != null) pending.addLast(current.getCause());
        }
        return errors;
    }

    private static String logSafeMessage(String message) {
        if (message == null) return "";
        String singleLine = message.replaceAll("[\\r\\n\\t]", " ");
        return singleLine.length() <= 1024 ? singleLine : singleLine.substring(0, 1024);
    }

    public enum Operation {
        ORDER_CREATE,
        ORDER_CANCEL,
        ACCOUNT_ONBOARD,
        ACCOUNT_CREATE,
        ACCOUNT_RENAME,
        COMPETITION_CREATE,
        AUTH_PROFILE_SYNC,
        AUTH_REFRESH_TOKEN_REVOKE
    }

    private record MessageEnvelope(int schemaVersion, String requestId, String operation,
            String occurredAt, Object payload) {}

    public record OrderCreatePayload(long userId, long accountId, Order.Source source,
            OrderCreateRequest request) {}

    public record OrderCancelPayload(long userId, long accountId, long orderId) {}

    public record AccountOnboardPayload(long userId, OnboardingRequest request) {}

    public record AccountCreatePayload(long userId, AccountCreateRequest request) {}

    public record AccountRenamePayload(long userId, long accountId, AccountNameUpdateRequest request) {}

    public record CompetitionCreatePayload(long userId, CompetitionCreateRequest request) {}

    public record AuthProfileSyncPayload(long providerUserId, String nickname, String profileImageUrl) {}

    public record AuthRefreshTokenRevokePayload(String tokenHash) {}
}
