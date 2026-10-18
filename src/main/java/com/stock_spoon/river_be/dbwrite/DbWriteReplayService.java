package com.stock_spoon.river_be.dbwrite;

import com.stock_spoon.river_be.account.dto.AccountCreateRequest;
import com.stock_spoon.river_be.account.dto.AccountNameUpdateRequest;
import com.stock_spoon.river_be.account.dto.OnboardingRequest;
import com.stock_spoon.river_be.competition.dto.CompetitionCreateRequest;
import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.entity.Order;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.QueryType;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.listener.QueryUtils;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** Captures the ordered DML and JDBC bind operations for rolled-back transactions. */
@Service
public class DbWriteReplayService implements QueryExecutionListener {
    private static final Logger log = LoggerFactory.getLogger(DbWriteReplayService.class);
    private static final String GLOBAL_MESSAGE_GROUP_ID = "db-write-replay-global";
    private static final int MAX_MESSAGE_BYTES = 1_048_576;
    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private final SqsClient sqs;
    private final boolean enabled;
    private final String queueUrl;
    private final Set<Integer> proxySqlErrorCodes;
    private final ThreadLocal<TransactionCapture> activeCapture = new ThreadLocal<>();
    private final ThreadLocal<ReplayReceipt> completedCapture = new ThreadLocal<>();

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

    @Override
    public void beforeQuery(ExecutionInfo executionInfo, List<QueryInfo> queryInfoList) {
        // The statement and bind values are complete in afterQuery, including failed executions.
    }

    @Override
    public void afterQuery(ExecutionInfo executionInfo, List<QueryInfo> queryInfoList) {
        if (!enabled || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

        var writes = queryInfoList.stream()
                .filter(queryInfo -> isReplayableWrite(queryInfo.getQuery()))
                .toList();
        boolean writerUnavailable = SqlWriteFailureClassifier.isWriterUnavailable(
                executionInfo.getThrowable(), proxySqlErrorCodes);
        if (writes.isEmpty() && !writerUnavailable) return;

        TransactionCapture capture = activeCapture.get();
        if (capture == null) {
            capture = new TransactionCapture(UUID.randomUUID().toString(), MDC.get("requestId"));
            activeCapture.set(capture);
            completedCapture.remove();
            TransactionCapture captured = capture;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    completeCapture(captured, status);
                }
            });
        }

        if (writerUnavailable) capture.writerUnavailable = true;
        if (capture.captureFailure != null) return;
        try {
            for (QueryInfo queryInfo : writes) {
                capture.statements.add(snapshot(queryInfo));
            }
        } catch (RuntimeException error) {
            // Keep the application's JDBC behavior unchanged. The transaction must not be
            // reported as queued if one of its bind values could not be serialized.
            capture.captureFailure = error;
        }
    }

    /**
     * Existing API handlers use this after their transactional service call has rolled back.
     * The request DTO is intentionally not serialized: the matching transaction SQL is already
     * captured at the JDBC boundary and queued from its completion callback.
     */
    public java.util.Optional<String> enqueueIfWriterUnavailable(
            Throwable failure, Operation operation, Object ignoredPayload) {
        boolean classifiedAsWriterUnavailable = SqlWriteFailureClassifier.isWriterUnavailable(failure, proxySqlErrorCodes);
        logObservedFailure(failure, operation, classifiedAsWriterUnavailable);
        if (!enabled || !classifiedAsWriterUnavailable) return java.util.Optional.empty();

        ReplayReceipt receipt = completedCapture.get();
        completedCapture.remove();
        String currentRequestId = MDC.get("requestId");
        if (receipt == null || !Objects.equals(receipt.correlationId(), currentRequestId)) {
            log.error("event=db_write_replay_capture_missing operation={} requestId={} cause=transaction_sql_not_captured",
                    operation, currentRequestId == null ? "none" : currentRequestId);
            return java.util.Optional.empty();
        }
        if (receipt.enqueueFailureType() != null) {
            throw new DbWriteReplayEnqueueException(
                    new IllegalStateException("SQS enqueue failed: " + receipt.enqueueFailureType()));
        }
        return java.util.Optional.of(receipt.replayRequestId());
    }

    private void completeCapture(TransactionCapture capture, int status) {
        activeCapture.remove();
        if (status != TransactionSynchronization.STATUS_ROLLED_BACK || !capture.writerUnavailable) return;

        try {
            if (capture.captureFailure != null) throw capture.captureFailure;
            if (capture.statements.isEmpty()) {
                throw new IllegalStateException("No replayable DML statements were captured for the failed transaction");
            }

            String replayRequestId = UUID.randomUUID().toString();
            var envelope = new MessageEnvelope(2, replayRequestId, capture.correlationId,
                    "SQL_TRANSACTION", Instant.now().toString(), capture.transactionId,
                    List.copyOf(capture.statements));
            String body = JSON.writeValueAsString(envelope);
            if (body.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
                throw new IllegalArgumentException("DB write replay message exceeds the SQS 1 MiB limit");
            }
            var response = sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .messageGroupId(GLOBAL_MESSAGE_GROUP_ID)
                    .messageDeduplicationId(replayRequestId)
                    .build());
            completedCapture.set(new ReplayReceipt(replayRequestId, capture.correlationId, null));
            log.warn("event=db_write_replay_queued operation=SQL_TRANSACTION requestId={} correlationId={} "
                            + "transactionId={} statementCount={} messageId={}",
                    replayRequestId, safeId(capture.correlationId), capture.transactionId,
                    capture.statements.size(), response.messageId());
        } catch (Exception error) {
            completedCapture.set(new ReplayReceipt(null, capture.correlationId,
                    error.getClass().getSimpleName()));
            log.error("event=db_write_replay_enqueue_failed operation=SQL_TRANSACTION correlationId={} "
                            + "transactionId={} statementCount={} causeType={}",
                    safeId(capture.correlationId), capture.transactionId, capture.statements.size(),
                    error.getClass().getSimpleName());
        }
    }

    private static SqlStatement snapshot(QueryInfo queryInfo) {
        List<List<ParameterSetOperation>> parameterSets = queryInfo.getParametersList();
        if (parameterSets == null || parameterSets.isEmpty()) {
            parameterSets = List.of(List.of());
        }
        var serializedParameterSets = new ArrayList<List<SerializedParameterOperation>>(parameterSets.size());
        for (List<ParameterSetOperation> parameterSet : parameterSets) {
            var operations = new ArrayList<SerializedParameterOperation>(parameterSet.size());
            for (ParameterSetOperation operation : parameterSet) {
                if (operation.getMethod() == null || operation.getArgs() == null) {
                    throw new IllegalArgumentException("JDBC bind operation is incomplete");
                }
                var arguments = new ArrayList<SerializedValue>(operation.getArgs().length);
                for (Object argument : operation.getArgs()) {
                    arguments.add(serializeValue(argument));
                }
                operations.add(new SerializedParameterOperation(operation.getMethod().getName(), arguments));
            }
            serializedParameterSets.add(List.copyOf(operations));
        }
        return new SqlStatement(queryInfo.getQuery(), List.copyOf(serializedParameterSets));
    }

    private static SerializedValue serializeValue(Object value) {
        if (value == null) return new SerializedValue("null", null);
        if (value instanceof byte[] bytes) {
            return new SerializedValue("byte[]", Base64.getEncoder().encodeToString(bytes));
        }
        if (value instanceof Calendar calendar) {
            return new SerializedValue("calendar", calendar.getTimeZone().getID());
        }
        if (value instanceof SQLType sqlType) {
            return new SerializedValue("sqlType", sqlType.getVendorTypeNumber());
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return new SerializedValue("timestamp", timestamp.toString());
        }
        if (value instanceof java.sql.Date date) {
            return new SerializedValue("date", date.toString());
        }
        if (value instanceof java.sql.Time time) {
            return new SerializedValue("time", time.toString());
        }
        if (value instanceof LocalDate || value instanceof LocalTime || value instanceof LocalDateTime
                || value instanceof OffsetDateTime || value instanceof ZonedDateTime || value instanceof Instant
                || value instanceof BigDecimal || value instanceof BigInteger || value instanceof UUID) {
            return new SerializedValue(value.getClass().getName(), value.toString());
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte || value instanceof Float || value instanceof Double) {
            return new SerializedValue(value.getClass().getName(), value);
        }
        if (value instanceof Enum<?> enumValue) {
            return new SerializedValue(value.getClass().getName(), enumValue.name());
        }
        throw new IllegalArgumentException("Unsupported JDBC bind value type: " + value.getClass().getName());
    }

    private static boolean isReplayableWrite(String query) {
        if (query == null || query.isBlank()) return false;
        QueryType queryType = QueryUtils.getQueryType(query);
        if (queryType == QueryType.INSERT || queryType == QueryType.UPDATE || queryType == QueryType.DELETE) return true;
        String normalized = QueryUtils.removeCommentAndWhiteSpace(query).stripLeading().toUpperCase(java.util.Locale.ROOT);
        return normalized.startsWith("REPLACE ") || normalized.startsWith("REPLACE\n")
                || normalized.startsWith("CALL ") || normalized.startsWith("CALL\n");
    }

    private static String safeId(String value) {
        return value == null || value.isBlank() ? "none" : value;
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

    private static List<SQLException> findSqlExceptions(Throwable failure) {
        if (failure == null) return List.of();
        var pending = new ArrayDeque<Throwable>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var errors = new ArrayList<SQLException>();
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

    private record MessageEnvelope(int schemaVersion, String requestId, String correlationId,
            String operation, String occurredAt, String transactionId, List<SqlStatement> statements) {}

    private record SqlStatement(String sql, List<List<SerializedParameterOperation>> parameterSets) {}

    private record SerializedParameterOperation(String method, List<SerializedValue> args) {}

    private record SerializedValue(String type, Object value) {}

    private record ReplayReceipt(String replayRequestId, String correlationId, String enqueueFailureType) {}

    private static final class TransactionCapture {
        private final String transactionId;
        private final String correlationId;
        private final List<SqlStatement> statements = new ArrayList<>();
        private boolean writerUnavailable;
        private RuntimeException captureFailure;

        private TransactionCapture(String transactionId, String correlationId) {
            this.transactionId = transactionId;
            this.correlationId = correlationId;
        }
    }

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
