package com.stock_spoon.river_be.config;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Holds JDBC write capture and transaction completion state for the current HTTP request.
 * Captured SQL and bind values are kept only in memory and are never written to logs.
 */
@Component
public class DbWriteReplayRequestContext {
    private final ThreadLocal<RequestState> current = new ThreadLocal<>();

    public void beginRequest(String requestId, String writeId) {
        current.set(new RequestState(requestId, writeId));
    }

    public void endRequest() {
        current.remove();
    }

    void recordSuccessfulWrite(Map<String, Object> statement, boolean mayDependOnGeneratedKey) {
        RequestState state = current.get();
        if (state == null) return;

        state.statements.add(statement);
        // A rolled-back auto-increment INSERT can receive a different ID on replay. Until
        // generated-key remapping exists, replaying later statements could corrupt references.
        if (mayDependOnGeneratedKey) state.replayable = false;
    }

    void markUnreplayableWrite() {
        RequestState state = current.get();
        if (state != null) state.replayable = false;
    }

    void observeFallbackFailure(SQLException error, List<Map<String, Object>> statements, boolean replayable) {
        RequestState state = current.get();
        if (state == null) return;

        if (state.proxySqlError != null && state.proxySqlError != error) {
            state.replayable = false;
        }
        state.proxySqlError = error;
        state.replayable &= replayable;
        if (statements == null || statements.isEmpty()) {
            state.replayable = false;
        } else {
            state.statements.addAll(statements);
        }
    }

    void markTransactionCommitted() {
        RequestState state = current.get();
        if (state != null) {
            state.committedTransactionSeen = true;
            state.rollbackConfirmed = false;
        }
    }

    void markTransactionRolledBack(boolean confirmed) {
        RequestState state = current.get();
        if (state != null) {
            state.rollbackCount++;
            state.rollbackConfirmed = confirmed && state.rollbackCount == 1;
        }
    }

    boolean canEnqueue(SQLException error) {
        RequestState state = current.get();
        return state != null
                && state.proxySqlError == error
                && state.rollbackConfirmed
                && !state.committedTransactionSeen
                && state.replayable
                && !state.statements.isEmpty()
                && state.accepted == null;
    }

    ReplaySnapshot snapshot(SQLException error) {
        RequestState state = current.get();
        if (state == null || state.proxySqlError != error) return null;

        String replayRequestId = UUID.randomUUID().toString();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 2);
        envelope.put("operation", "SQL_TRANSACTION");
        envelope.put("requestId", replayRequestId);
        envelope.put("transactionId", replayRequestId);
        envelope.put("httpRequestId", state.requestId);
        if (state.writeId != null && !state.writeId.isBlank()) {
            envelope.put("writeId", state.writeId);
        }
        envelope.put("statements", List.copyOf(state.statements));
        return new ReplaySnapshot(replayRequestId, replayRequestId, state.requestId, envelope);
    }

    AcceptedReplay acceptedReplay(SQLException error) {
        RequestState state = current.get();
        if (state == null || state.proxySqlError != error) return null;
        return state.accepted;
    }

    void markEnqueued(SQLException error, AcceptedReplay accepted) {
        RequestState state = current.get();
        if (state != null && state.proxySqlError == error) state.accepted = accepted;
    }

    private static final class RequestState {
        private final String requestId;
        @SuppressWarnings("unused")
        private final String writeId;
        private final List<Map<String, Object>> statements = new ArrayList<>();
        private SQLException proxySqlError;
        private boolean rollbackConfirmed;
        private boolean committedTransactionSeen;
        private int rollbackCount;
        private boolean replayable = true;
        private AcceptedReplay accepted;

        private RequestState(String requestId, String writeId) {
            this.requestId = requestId;
            this.writeId = writeId;
        }
    }

    record ReplaySnapshot(
            String replayRequestId,
            String transactionId,
            String httpRequestId,
            Map<String, Object> envelope) {
    }

    record AcceptedReplay(
            String replayRequestId,
            String transactionId,
            String httpRequestId,
            String messageId) {
    }
}
