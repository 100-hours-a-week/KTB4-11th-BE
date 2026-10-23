package com.stock_spoon.river_be.config;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Holds JDBC write capture and transaction completion state for the current HTTP request.
 * Captured SQL and bind values are kept only in memory and are never written to logs.
 */
@Component
public class DbWriteReplayRequestContext {
    private final ThreadLocal<RequestState> current = new ThreadLocal<>();
    private final Object transactionResourceKey = new Object();

    public void beginRequest(String requestId, String writeId) {
        current.set(new RequestState(requestId, writeId));
    }

    public void endRequest() {
        current.remove();
    }

    void recordSuccessfulWrite(Map<String, Object> statement, boolean mayDependOnGeneratedKey) {
        TransactionState transaction = currentTransactionState();
        if (transaction == null) {
            RequestState request = current.get();
            if (request != null) request.untrackedDmlObserved = true;
            return;
        }

        transaction.dmlObserved = true;
        transaction.statements.add(statement);
        // A rolled-back auto-increment INSERT can receive a different ID on replay. Until
        // generated-key remapping exists, replaying later statements could corrupt references.
        if (mayDependOnGeneratedKey) transaction.replayable = false;
    }

    void markUnreplayableWrite() {
        TransactionState transaction = currentTransactionState();
        if (transaction == null) {
            RequestState request = current.get();
            if (request != null) request.untrackedDmlObserved = true;
            return;
        }
        transaction.dmlObserved = true;
        transaction.replayable = false;
    }

    void observeFallbackFailure(SQLException error, List<Map<String, Object>> statements,
            boolean replayable, boolean dmlObserved) {
        RequestState request = current.get();
        if (request == null) return;

        TransactionState transaction = currentTransactionState();
        if (transaction == null) {
            request.untrackedFallbackError = error;
            return;
        }

        transaction.dmlObserved |= dmlObserved;
        if (transaction.proxySqlError != null && transaction.proxySqlError != error) {
            transaction.replayable = false;
        }
        transaction.proxySqlError = error;
        transaction.replayable &= replayable;
        if (statements != null) {
            // The failing statement is included when it is a DML statement. An empty list is
            // valid when an earlier DML in this same transaction was followed by a failing read.
            transaction.statements.addAll(statements);
        }
    }

    boolean canEnqueue(SQLException error) {
        return enqueueBlockReason(error) == null;
    }

    String enqueueBlockReason(SQLException error) {
        RequestState request = current.get();
        if (request == null) return "request_context_missing";

        TransactionState transaction = findTransaction(request, error);
        if (transaction == null) {
            return request.untrackedFallbackError == error
                    ? "transaction_tracking_unavailable"
                    : "fallback_error_not_observed";
        }
        if (transaction.proxySqlError != error) return "fallback_error_not_observed";
        if (transaction.accepted != null) return "already_accepted";
        if (!transaction.completionObserved
                || transaction.completionStatus != TransactionSynchronization.STATUS_ROLLED_BACK) {
            if (transaction.completionObserved
                    && transaction.completionStatus == TransactionSynchronization.STATUS_COMMITTED) {
                return "failed_transaction_not_rolled_back";
            }
            return "rollback_not_confirmed";
        }
        if (!transaction.dmlObserved) return "no_dml_before_fallback";
        if (!transaction.replayable) return "dml_not_replayable";
        if (transaction.statements.isEmpty()) return "no_captured_sql";
        return null;
    }

    ReplaySnapshot snapshot(SQLException error) {
        RequestState request = current.get();
        if (request == null) return null;
        TransactionState transaction = findTransaction(request, error);
        if (transaction == null || transaction.proxySqlError != error) return null;

        String replayRequestId = UUID.randomUUID().toString();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 2);
        envelope.put("operation", "SQL_TRANSACTION");
        envelope.put("requestId", replayRequestId);
        envelope.put("transactionId", transaction.transactionId);
        envelope.put("httpRequestId", request.requestId);
        if (request.writeId != null && !request.writeId.isBlank()) {
            envelope.put("writeId", request.writeId);
        }
        envelope.put("statements", List.copyOf(transaction.statements));
        return new ReplaySnapshot(
                replayRequestId, transaction.transactionId, request.requestId, envelope);
    }

    AcceptedReplay acceptedReplay(SQLException error) {
        RequestState request = current.get();
        if (request == null) return null;
        TransactionState transaction = findTransaction(request, error);
        if (transaction == null || transaction.proxySqlError != error) return null;
        return transaction.accepted;
    }

    void markEnqueued(SQLException error, AcceptedReplay accepted) {
        RequestState request = current.get();
        if (request == null) return;
        TransactionState transaction = findTransaction(request, error);
        if (transaction != null && transaction.proxySqlError == error) {
            transaction.accepted = accepted;
        }
    }

    private TransactionState currentTransactionState() {
        RequestState request = current.get();
        if (request == null
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return null;
        }

        Object existing = TransactionSynchronizationManager.getResource(transactionResourceKey);
        if (existing instanceof TransactionState transaction) return transaction;
        if (existing != null) return null;

        TransactionState transaction = new TransactionState(UUID.randomUUID().toString());
        request.transactions.add(transaction);
        try {
            TransactionSynchronizationManager.bindResource(transactionResourceKey, transaction);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    transaction.completionObserved = true;
                    transaction.completionStatus = status;
                    TransactionSynchronizationManager.unbindResourceIfPossible(transactionResourceKey);
                }
            });
            return transaction;
        } catch (RuntimeException trackingFailure) {
            TransactionSynchronizationManager.unbindResourceIfPossible(transactionResourceKey);
            request.transactions.remove(transaction);
            return null;
        }
    }

    private static TransactionState findTransaction(RequestState request, SQLException error) {
        for (int index = request.transactions.size() - 1; index >= 0; index--) {
            TransactionState transaction = request.transactions.get(index);
            if (transaction.proxySqlError == error) return transaction;
        }
        return null;
    }

    private static final class RequestState {
        private final String requestId;
        private final String writeId;
        private final List<TransactionState> transactions = new ArrayList<>();
        private boolean untrackedDmlObserved;
        private SQLException untrackedFallbackError;

        private RequestState(String requestId, String writeId) {
            this.requestId = requestId;
            this.writeId = writeId;
        }
    }

    private static final class TransactionState {
        private final String transactionId;
        private final List<Map<String, Object>> statements = new ArrayList<>();
        private boolean dmlObserved;
        private SQLException proxySqlError;
        private boolean replayable = true;
        private boolean completionObserved;
        private int completionStatus = TransactionSynchronization.STATUS_UNKNOWN;
        private AcceptedReplay accepted;

        private TransactionState(String transactionId) {
            this.transactionId = transactionId;
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
