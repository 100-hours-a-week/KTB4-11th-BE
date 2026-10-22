package com.stock_spoon.river_be.config;

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
 * Adds log-only commit/rollback observations to Spring-managed transactions.
 * No SQL, schema, transaction boundary, or business behavior is changed.
 */
@Configuration(proxyBeanMethods = false)
public class DbMigrationWriteTransactionAuditConfiguration {
    private static final Logger log =
            LoggerFactory.getLogger(DbMigrationWriteTransactionAuditConfiguration.class);

    @Bean
    static BeanPostProcessor dbMigrationWriteTransactionAuditConfigurer(
            DbWriteReplayRequestContext writeReplayContext) {
        TransactionExecutionListener listener = new TransactionExecutionListener() {
            @Override
            public void afterCommit(TransactionExecution transaction, Throwable failure) {
                writeReplayContext.markTransactionCommitted();
                logOutcome(transaction, failure == null ? "commit_confirmed" : "commit_unconfirmed", failure);
            }

            @Override
            public void afterRollback(TransactionExecution transaction, Throwable failure) {
                writeReplayContext.markTransactionRolledBack(failure == null);
                logOutcome(transaction, failure == null ? "rollback_confirmed" : "rollback_unconfirmed", failure);
            }
        };

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

    private static void logOutcome(TransactionExecution transaction, String outcome, Throwable failure) {
        String writeId = MDC.get(DbMigrationWriteAuditFilter.MDC_KEY);
        if (writeId == null) return;

        String requestId = MDC.get("requestId");
        String failureType = failure == null ? "none" : failure.getClass().getSimpleName();
        log.info("event=db_migration_write_transaction writeId={} requestId={} outcome={} "
                        + "newTransaction={} readOnly={} failureType={}",
                writeId, requestId == null ? "none" : requestId, outcome,
                transaction.isNewTransaction(), transaction.isReadOnly(), failureType);
    }
}
