package com.stock_spoon.river_be.config;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Tracks Spring transaction completion for replay and logs commit/rollback outcomes.
 */
@Configuration(proxyBeanMethods = false)
public class DbMigrationWriteTransactionAuditConfiguration {
    private static final Logger log =
            LoggerFactory.getLogger(DbMigrationWriteTransactionAuditConfiguration.class);

    @Bean
    static BeanPostProcessor dbMigrationWriteTransactionAuditConfigurer(
            DbWriteReplayRequestContext writeReplayContext) {
        TransactionExecutionListener listener = replayTransactionListener(writeReplayContext);

        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ConfigurableTransactionManager manager) {
                    manager.addListener(listener);
                }
                return bean;
            }
        };
    }

    static TransactionExecutionListener replayTransactionListener(
            DbWriteReplayRequestContext writeReplayContext) {
        Map<TransactionExecution, Long> transactionStartedAtNanos =
                Collections.synchronizedMap(new IdentityHashMap<>());
        return new TransactionExecutionListener() {
            @Override
            public void afterBegin(TransactionExecution transaction, Throwable failure) {
                if (failure == null) {
                    transactionStartedAtNanos.put(transaction, System.nanoTime());
                    writeReplayContext.beginTransaction(transaction);
                } else {
                    writeReplayContext.observeTransactionBeginFailure(failure);
                }
            }

            @Override
            public void afterCommit(TransactionExecution transaction, Throwable failure) {
                String transactionId = writeReplayContext.transactionId(transaction);
                writeReplayContext.markTransactionCommitted(transaction, failure == null);
                logOutcome(transaction, failure == null ? "commit_confirmed" : "commit_unconfirmed", failure,
                        elapsedMillis(transactionStartedAtNanos.remove(transaction)), transactionId);
            }

            @Override
            public void afterRollback(TransactionExecution transaction, Throwable failure) {
                String transactionId = writeReplayContext.transactionId(transaction);
                writeReplayContext.markTransactionRolledBack(transaction, failure);
                logOutcome(transaction, failure == null ? "rollback_confirmed" : "rollback_unconfirmed", failure,
                        elapsedMillis(transactionStartedAtNanos.remove(transaction)), transactionId);
            }
        };
    }

    private static long elapsedMillis(Long startedAtNanos) {
        return startedAtNanos == null ? -1 : (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private static void logOutcome(TransactionExecution transaction, String outcome, Throwable failure,
            long transactionElapsedMs, String transactionId) {
        String writeId = MDC.get(DbMigrationWriteAuditFilter.MDC_KEY);
        // Normal successful transactions are too frequent to log unless explicitly tagged.
        // Always record uncertain completion so a failed rollback is visible without a write ID.
        if (writeId == null && failure == null) return;

        String requestId = MDC.get("requestId");
        String failureType = failure == null ? "none" : failure.getClass().getSimpleName();
        SQLException sqlFailure = DbMigrationWriteFallbackClassifier
                .findReadRetryableConnectionFailure(failure);
        int sqlErrorCode = sqlFailure == null ? 0 : sqlFailure.getErrorCode();
        String sqlState = sqlFailure == null || sqlFailure.getSQLState() == null
                ? "none" : sqlFailure.getSQLState();
        var event = failure == null ? log.atInfo() : log.atWarn();
        event.log("event=db_migration_write_transaction writeId={} requestId={} transactionId={} outcome={} "
                        + "newTransaction={} readOnly={} transactionElapsedMs={} failureType={} sqlErrorCode={} sqlState={}",
                writeId == null ? "none" : writeId, requestId == null ? "none" : requestId,
                transactionId, outcome,
                transaction.isNewTransaction(), transaction.isReadOnly(), transactionElapsedMs,
                failureType, sqlErrorCode, sqlState);
    }
}
