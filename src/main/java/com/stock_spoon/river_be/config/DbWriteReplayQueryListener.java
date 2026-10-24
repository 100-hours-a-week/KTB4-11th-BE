package com.stock_spoon.river_be.config;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Captures ordered transactional DML and JDBC bind operations for replay. */
public class DbWriteReplayQueryListener implements QueryExecutionListener {
    private static final Pattern WRITE_SQL =
            Pattern.compile("(?i)^(INSERT|UPDATE|DELETE|REPLACE)\\b");
    private static final Pattern INSERT_OR_REPLACE_SQL =
            Pattern.compile("(?i)^(INSERT|REPLACE)\\b");

    private final DbWriteReplayRequestContext requestContext;

    public DbWriteReplayQueryListener(DbWriteReplayRequestContext requestContext) {
        this.requestContext = requestContext;
    }

    @Override
    public void beforeQuery(ExecutionInfo executionInfo, List<QueryInfo> queryInfoList) {
        // Capture only after execution so the transaction's rollback result is known separately.
    }

    @Override
    public void afterQuery(ExecutionInfo executionInfo, List<QueryInfo> queryInfoList) {
        Throwable failure = executionInfo.getThrowable();
        SQLException fallbackError = DbMigrationWriteFallbackClassifier.findWriteFallbackFailure(failure);
        List<Map<String, Object>> failedWrites = new ArrayList<>();
        boolean replayable = true;
        boolean sawWrite = false;

        if (queryInfoList != null) {
            for (QueryInfo queryInfo : queryInfoList) {
                if (queryInfo == null || !isWriteSql(queryInfo.getQuery())) continue;
                sawWrite = true;
                if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                    requestContext.markUnreplayableWrite();
                    continue;
                }
                if (failure == null) {
                    try {
                        requestContext.recordSuccessfulWrite(
                                toReplayStatement(queryInfo), mayDependOnGeneratedKey(queryInfo.getQuery()));
                    } catch (RuntimeException unsupportedBindValue) {
                        requestContext.markUnreplayableWrite();
                    }
                    continue;
                }
                if (fallbackError == null) {
                    requestContext.markUnreplayableWrite();
                    continue;
                }
                try {
                    failedWrites.add(toReplayStatement(queryInfo));
                } catch (RuntimeException unsupportedBindValue) {
                    replayable = false;
                }
            }
        }

        if (fallbackError != null) {
            requestContext.observeFallbackFailure(
                    fallbackError, failedWrites, replayable, sawWrite);
        }
    }

    private static boolean mayDependOnGeneratedKey(String sql) {
        return INSERT_OR_REPLACE_SQL.matcher(stripLeadingComments(sql)).find();
    }

    private static boolean isWriteSql(String sql) {
        if (sql == null) return false;
        String candidate = stripLeadingComments(sql);
        return WRITE_SQL.matcher(candidate).find();
    }

    private static String stripLeadingComments(String sql) {
        String candidate = sql.stripLeading();
        boolean removed;
        do {
            removed = false;
            if (candidate.startsWith("/*")) {
                int end = candidate.indexOf("*/", 2);
                if (end >= 0) {
                    candidate = candidate.substring(end + 2).stripLeading();
                    removed = true;
                }
            } else if (candidate.startsWith("--") || candidate.startsWith("#")) {
                int end = candidate.indexOf('\n');
                candidate = end < 0 ? "" : candidate.substring(end + 1).stripLeading();
                removed = true;
            }
        } while (removed);
        return candidate;
    }

    private static Map<String, Object> toReplayStatement(QueryInfo queryInfo) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("sql", queryInfo.getQuery());

        List<List<ParameterSetOperation>> capturedParameterSets = queryInfo.getParametersList();
        List<List<Map<String, Object>>> parameterSets = new ArrayList<>();
        if (capturedParameterSets == null || capturedParameterSets.isEmpty()) {
            parameterSets.add(List.of());
        } else {
            for (List<ParameterSetOperation> operations : capturedParameterSets) {
                List<Map<String, Object>> serializedOperations = new ArrayList<>();
                if (operations != null) {
                    for (ParameterSetOperation operation : operations) {
                        serializedOperations.add(toReplayOperation(operation));
                    }
                }
                parameterSets.add(serializedOperations);
            }
        }
        statement.put("parameterSets", parameterSets);
        return statement;
    }

    private static Map<String, Object> toReplayOperation(ParameterSetOperation operation) {
        if (operation == null || operation.getMethod() == null
                || !operation.getMethod().getName().startsWith("set")) {
            throw new IllegalArgumentException("Unsupported JDBC parameter operation");
        }
        Object[] args = operation.getArgs();
        if (args == null || args.length == 0) {
            throw new IllegalArgumentException("JDBC parameter operation has no index");
        }

        List<Map<String, Object>> serializedArgs = new ArrayList<>();
        // The replay consumer needs the index and bound value. JDBC overload hints and Calendars
        // are intentionally omitted; unsupported bound values fail closed.
        serializedArgs.add(toTypedValue(args[0]));
        if (args.length > 1) serializedArgs.add(toTypedValue(args[1]));

        Map<String, Object> serialized = new LinkedHashMap<>();
        serialized.put("method", operation.getMethod().getName());
        serialized.put("args", serializedArgs);
        return serialized;
    }

    private static Map<String, Object> toTypedValue(Object value) {
        Map<String, Object> typed = new LinkedHashMap<>();
        if (value == null) {
            typed.put("type", "null");
            typed.put("value", null);
            return typed;
        }

        String type = value.getClass().getName();
        String serializedValue;
        if (value instanceof byte[] bytes) {
            type = "byte[]";
            serializedValue = Base64.getEncoder().encodeToString(bytes);
        } else if (value instanceof BigDecimal decimal) {
            serializedValue = decimal.toPlainString();
        } else if (value instanceof BigInteger integer) {
            serializedValue = integer.toString();
        } else if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double
                || value instanceof Boolean || value instanceof CharSequence
                || value instanceof Character || value instanceof UUID
                || value instanceof Enum<?> || value instanceof TemporalAccessor) {
            serializedValue = value instanceof Enum<?> enumValue
                    ? enumValue.name()
                    : value.toString();
        } else if (value instanceof java.sql.Date date) {
            serializedValue = date.toLocalDate().toString();
        } else if (value instanceof Time time) {
            serializedValue = time.toLocalTime().toString();
        } else if (value instanceof Timestamp timestamp) {
            serializedValue = timestamp.toLocalDateTime().toString();
        } else {
            throw new IllegalArgumentException("Unsupported JDBC bind value type");
        }

        typed.put("type", type);
        typed.put("value", serializedValue);
        return typed;
    }
}
