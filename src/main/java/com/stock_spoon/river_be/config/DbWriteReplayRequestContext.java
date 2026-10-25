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
        beginRequest(requestId, writeId, null);
    }

    public void beginRequest(String requestId, String writeId, String clientIdempotencyKey) {
        current.set(new RequestState(requestId, writeId, clientIdempotencyKey));
    }

    String clientIdempotencyKey() {
        RequestState request = current.get();
        return request == null ? null : request.clientIdempotencyKey;
    }

    String requestReplayId() {
        RequestState request = current.get();
        if (request == null) throw new IllegalStateException("Replay request context is missing");
        return request.replayRequestId;
    }

    void captureBusinessCommand(CapturedBusinessCommand command) {
        RequestState request = current.get();
        if (request == null) throw new IllegalStateException("Replay request context is missing");
        if (request.businessCommand != null) throw new IllegalStateException("Replay command already captured");
        request.businessCommand = command;
    }

    boolean hasBusinessCommand() {
        RequestState request = current.get();
        return request != null && request.businessCommand != null;
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

    void observeTransactionBeginFailure(Throwable failure) {
        RequestState request = current.get();
        if (request == null) return;
        request.transactionBeginFailureObserved = true;
        request.untrackedFallbackError =
                DbMigrationWriteFallbackClassifier.findWriteFallbackFailure(failure);
    }

    void markTransactionCommitted(TransactionExecution execution, boolean confirmed) {
        completeTransaction(execution, confirmed ? CompletionOutcome.COMMITTED : CompletionOutcome.UNKNOWN, false);
    }

    void markTransactionRolledBack(TransactionExecution execution, Throwable rollbackFailure) {
        RequestState request = current.get();
        if (request != null && execution != null) {
            TransactionState transaction = request.transactionsByExecution.get(execution);
            if (transaction != null) {
                transaction.rollbackAttempted = true;
                if (rollbackFailure != null && transaction.proxySqlError == null) {
                    transaction.proxySqlError =
                            DbMigrationWriteFallbackClassifier.findWriteFallbackFailure(rollbackFailure);
                }
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

    /** Record before JDBC execution: a lost response must never look like a pre-DML failure. */
    void observeWriteAttempt() {
        RequestState request = current.get();
        if (request == null) return;

        TransactionState transaction = currentTransactionState(request);
        if (transaction == null) request.untrackedWriteObserved = true;
        else transaction.dmlObserved = true;
    }

    void recordSuccessfulWrite(Map<String, Object> statement, boolean mayDependOnGeneratedKey) {
        RequestState request = current.get();
        if (request == null) return;

        TransactionState transaction = currentTransactionState(request);
        if (transaction == null) {
            request.untrackedWriteObserved = true;
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
            request.untrackedWriteObserved = true;
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
            return new ReplayDiagnostics("none", false, "unknown", false, false, 0, false, false);
        }

        TransactionState transaction = findTransaction(request, error);
        if (transaction == null) {
            String transactionId = request.untrackedFallbackError == error ? "untracked" : "none";
            return new ReplayDiagnostics(transactionId, false, "unknown", false, false, 0,
                    false, request.untrackedWriteObserved);
        }

        return new ReplayDiagnostics(transaction.transactionId, transaction.dmlObserved,
                transaction.completionOutcome.name().toLowerCase(Locale.ROOT),
                transaction.rollbackConfirmed, transaction.replayable, transaction.statements.size(),
                transaction.rollbackAttempted, request.untrackedWriteObserved);
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
        if (!transaction.dmlObserved) return "no_dml_before_fallback";
        if (!transaction.completionObserved || !transaction.rollbackAttempted
                || transaction.completionOutcome != CompletionOutcome.ROLLED_BACK
                || !transaction.rollbackConfirmed) {
            return "rollback_not_confirmed";
        }
        if (!transaction.replayable) return "dml_not_replayable";
        if (transaction.statements.isEmpty()) return "no_captured_sql";
        return null;
    }

    /** Preserve the entire command, even when SQL has not been generated or its outcome is uncertain. */
    ReplaySnapshot snapshotBusinessCommand(SQLException error) {
        RequestState request = current.get();
        if (request == null || request.businessCommand == null) return null;
        if (request.businessSnapshot != null) return request.businessSnapshot;

        CapturedBusinessCommand command = request.businessCommand;
        ReplayDiagnostics diagnostics = diagnostics(error);
        String safety = commandReplaySafety(request);
        String policy = safety.equals("UNCONFIRMED_OR_PARTIAL")
                ? "RECONCILIATION_REQUIRED" : "REEXECUTE_COMMAND";
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 3);
        envelope.put("operation", "BUSINESS_COMMAND");
        envelope.put("consumerContract", "DB_WRITE_BUSINESS_COMMAND_V1");
        envelope.put("requestId", request.replayRequestId);
        envelope.put("transactionId", diagnostics.transactionId());
        envelope.put("httpRequestId", request.requestId);
        if (request.writeId != null && !request.writeId.isBlank()) envelope.put("writeId", request.writeId);
        envelope.put("idempotencyKey", command.idempotencyKey());
        envelope.put("idempotencyKeySource", command.keySource());
        envelope.put("payloadHash", command.payloadHash());
        envelope.put("replaySafety", safety);
        envelope.put("replayPolicy", policy);
        envelope.put("requiresBusinessValidation", true);
        envelope.put("businessCommand", command.command());
        // These SQL statements are evidence, not an alternative execution plan. Never execute
        // both captured SQL and the command: the failed workflow may have generated only a prefix.
        envelope.put("capturedStatementsArePartial", true);
        envelope.put("capturedStatements", request.transactions.stream()
                .flatMap(transaction -> transaction.statements.stream()).toList());
        envelope.put("sourceTransactions", request.transactions.stream().map(transaction -> Map.of(
                "transactionId", transaction.transactionId,
                "dmlObserved", transaction.dmlObserved,
                "completionObserved", transaction.completionObserved,
                "outcome", transaction.completionOutcome.name(),
                "rollbackConfirmed", transaction.rollbackConfirmed)).toList());
        envelope.put("sqlErrorCode", error.getErrorCode());
        if (error.getSQLState() != null) envelope.put("sqlState", error.getSQLState());
        request.businessSnapshot = new ReplaySnapshot(request.replayRequestId,
                diagnostics.transactionId(), request.requestId, envelope);
        return request.businessSnapshot;
    }

    private static String commandReplaySafety(RequestState request) {
        if (request.untrackedWriteObserved || !request.activeTransactions.isEmpty()) {
            return "UNCONFIRMED_OR_PARTIAL";
        }
        boolean failureObserved = request.transactionBeginFailureObserved;
        boolean mutationObserved = false;
        for (TransactionState transaction : request.transactions) {
            failureObserved |= transaction.proxySqlError != null;
            if (!transaction.completionObserved
                    || (transaction.completionOutcome == CompletionOutcome.UNKNOWN && !transaction.rollbackAttempted)) {
                return "UNCONFIRMED_OR_PARTIAL";
            }
            if (transaction.dmlObserved) {
                mutationObserved = true;
                if (transaction.completionOutcome != CompletionOutcome.ROLLED_BACK || !transaction.rollbackConfirmed) {
                    return "UNCONFIRMED_OR_PARTIAL";
                }
            }
        }
        if (!failureObserved) return "UNCONFIRMED_OR_PARTIAL";
        return mutationObserved ? "CONFIRMED_ROLLBACK" : "NO_DML_OBSERVED";
    }

    Map<String, Object> acceptedCommandMetadata() {
        RequestState request = current.get();
        if (request == null || request.businessSnapshot == null) return Map.of();
        return Map.of("operation", "BUSINESS_COMMAND",
                "replaySafety", request.businessSnapshot.envelope().get("replaySafety"),
                "replayPolicy", request.businessSnapshot.envelope().get("replayPolicy"));
    }

    ReplaySnapshot snapshot(SQLException error) {
        RequestState request = current.get();
        if (request == null) return null;

        TransactionState transaction = findTransaction(request, error);
        if (transaction == null || transaction.proxySqlError != error
                || enqueueBlockReason(error) != null) return null;

        String replayRequestId = transaction.replayRequestId;
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
        if (request.businessAccepted != null) return request.businessAccepted;

        TransactionState transaction = findTransaction(request, error);
        if (transaction == null || transaction.proxySqlError != error) return null;
        return transaction.accepted;
    }

    void markEnqueued(SQLException error, AcceptedReplay accepted) {
        RequestState request = current.get();
        if (request == null) return;
        if (request.businessSnapshot != null) {
            request.businessAccepted = accepted;
            return;
        }

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
        private boolean untrackedWriteObserved;
        private boolean transactionBeginFailureObserved;
        private final String replayRequestId = UUID.randomUUID().toString();
        private final String clientIdempotencyKey;
        private CapturedBusinessCommand businessCommand;
        private ReplaySnapshot businessSnapshot;
        private AcceptedReplay businessAccepted;

        private RequestState(String requestId, String writeId, String clientIdempotencyKey) {
            this.requestId = requestId;
            this.writeId = writeId;
            this.clientIdempotencyKey = clientIdempotencyKey;
        }
    }

    private static final class TransactionState {
        private final String transactionId;
        // Preserve the transport identity even when SendMessage's response is lost and the
        // resolver runs again. A newly generated ID would defeat FIFO/receipt deduplication.
        private final String replayRequestId = UUID.randomUUID().toString();
        private final List<Map<String, Object>> statements = new ArrayList<>();
        private boolean dmlObserved;
        private SQLException proxySqlError;
        private boolean replayable = true;
        private boolean generatedKeyInsertSeen;
        private boolean completionObserved;
        private boolean rollbackConfirmed;
        private boolean rollbackAttempted;
        private CompletionOutcome completionOutcome = CompletionOutcome.UNKNOWN;
        private AcceptedReplay accepted;

        private TransactionState(String transactionId) {
            this.transactionId = transactionId;
        }
    }

    record CapturedBusinessCommand(Object command, String idempotencyKey, String keySource, String payloadHash) {
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
            int capturedStatementCount,
            boolean rollbackAttempted,
            boolean untrackedWriteObserved) {
    }
}
