package com.stock_spoon.river_be.config;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;

/**
 * Holds captured SQL and completion state for each Spring transaction in one HTTP request.
 * Captured SQL and bind values stay in memory and are never written to logs.
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

    void beginTransaction(TransactionExecution execution) {
        RequestState request = current.get();
        if (request == null || execution == null || !execution.isNewTransaction()) return;

        TransactionState transaction = new TransactionState(UUID.randomUUID().toString());
        request.transactions.add(transaction);
        request.transactionsByExecution.put(execution, transaction);
        request.activeTransactions.add(transaction);
    }

    void markTransactionCommitted(TransactionExecution execution, boolean confirmed) {
        completeTransaction(execution, confirmed ? CompletionOutcome.COMMITTED : CompletionOutcome.UNKNOWN, false);
    }

    void markTransactionRolledBack(TransactionExecution execution, Throwable rollbackFailure) {
        RequestState request = current.get();
        if (request != null && execution != null && rollbackFailure != null) {
            TransactionState transaction = request.transactionsByExecution.get(execution);
            if (transaction != null && transaction.proxySqlError == null) {
                transaction.proxySqlError =
                        DbMigrationWriteFallbackClassifier.findWriteFallbackFailure(rollbackFailure);
            }
        }

        boolean confirmed = rollbackFailure == null;
        completeTransaction(execution,
                confirmed ? CompletionOutcome.ROLLED_BACK : CompletionOutcome.UNKNOWN, confirmed);
    }

    private void completeTransaction(
            TransactionExecution execution, CompletionOutcome outcome, boolean rollbackConfirmed) {
        RequestState request = current.get();
        if (request == null || execution == null) return;

        TransactionState transaction = request.transactionsByExecution.remove(execution);
        if (transaction == null) return;

        transaction.completionObserved = true;
        transaction.completionOutcome = outcome;
        transaction.rollbackConfirmed = rollbackConfirmed;
        request.activeTransactions.remove(transaction);
    }

    void recordSuccessfulWrite(Map<String, Object> statement, boolean mayDependOnGeneratedKey) {
        RequestState request = current.get();
        if (request == null) return;

        TransactionState transaction = currentTransactionState(request);
        if (transaction == null) {
            return;
        }

        transaction.dmlObserved = true;
        // If an earlier INSERT returned a generated key, later writes may refer to that key.
        // Keep the transaction out of replay until generated-key remapping is implemented.
        if (transaction.generatedKeyInsertSeen) transaction.replayable = false;
        transaction.statements.add(statement);
        if (mayDependOnGeneratedKey) transaction.generatedKeyInsertSeen = true;
    }

    void markUnreplayableWrite() {
        RequestState request = current.get();
        if (request == null) return;

        TransactionState transaction = currentTransactionState(request);
        if (transaction == null) {
            return;
        }
        transaction.dmlObserved = true;
        transaction.replayable = false;
    }

    void observeFallbackFailure(SQLException error, List<Map<String, Object>> statements,
            boolean replayable, boolean dmlObserved) {
        RequestState request = current.get();
        if (request == null) return;

        TransactionState transaction = currentTransactionState(request);
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
        if (statements != null && !statements.isEmpty()) {
            if (transaction.generatedKeyInsertSeen) transaction.replayable = false;
            transaction.statements.addAll(statements);
        }
    }

    SQLException observedFallbackFailure() {
        RequestState request = current.get();
        if (request == null) return null;

        for (int index = request.transactions.size() - 1; index >= 0; index--) {
            SQLException error = request.transactions.get(index).proxySqlError;
            if (error != null) return error;
        }
        return request.untrackedFallbackError;
    }

    ReplayDiagnostics diagnostics(SQLException error) {
        RequestState request = current.get();
        if (request == null) {
            return new ReplayDiagnostics("none", false, "unknown", false, false, 0);
        }

        TransactionState transaction = findTransaction(request, error);
        if (transaction == null) {
            String transactionId = request.untrackedFallbackError == error ? "untracked" : "none";
            return new ReplayDiagnostics(transactionId, false, "unknown", false, false, 0);
        }

        return new ReplayDiagnostics(transaction.transactionId, transaction.dmlObserved,
                transaction.completionOutcome.name().toLowerCase(Locale.ROOT),
                transaction.rollbackConfirmed, transaction.replayable, transaction.statements.size());
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
        if (transaction.completionOutcome == CompletionOutcome.COMMITTED) {
            return "transaction_already_committed";
        }
        if (!transaction.completionObserved
                || transaction.completionOutcome != CompletionOutcome.ROLLED_BACK
                || !transaction.rollbackConfirmed) {
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

    private static TransactionState currentTransactionState(RequestState request) {
        if (request.activeTransactions.isEmpty()) return null;
        return request.activeTransactions.get(request.activeTransactions.size() - 1);
    }

    private static TransactionState findTransaction(RequestState request, SQLException error) {
        for (int index = request.transactions.size() - 1; index >= 0; index--) {
            TransactionState transaction = request.transactions.get(index);
            if (transaction.proxySqlError == error) return transaction;
        }
        return null;
    }

    private enum CompletionOutcome {
        UNKNOWN,
        COMMITTED,
        ROLLED_BACK
    }

    private static final class RequestState {
        private final String requestId;
        private final String writeId;
        private final List<TransactionState> transactions = new ArrayList<>();
        private final List<TransactionState> activeTransactions = new ArrayList<>();
        private final IdentityHashMap<TransactionExecution, TransactionState> transactionsByExecution =
                new IdentityHashMap<>();
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
        private boolean generatedKeyInsertSeen;
        private boolean completionObserved;
        private boolean rollbackConfirmed;
        private CompletionOutcome completionOutcome = CompletionOutcome.UNKNOWN;
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

    record ReplayDiagnostics(
            String transactionId,
            boolean dmlObserved,
            String transactionOutcome,
            boolean rollbackConfirmed,
            boolean replayable,
            int capturedStatementCount) {
    }
}
