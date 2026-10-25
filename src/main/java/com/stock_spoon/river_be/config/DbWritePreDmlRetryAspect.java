package com.stock_spoon.river_be.config;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Retries outside service transactions, before any SQL mutation has been attempted. */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "db-write-replay", name = "enabled", havingValue = "true")
public class DbWritePreDmlRetryAspect {
    private static final Logger log = LoggerFactory.getLogger(DbWritePreDmlRetryAspect.class);

    private final DbWriteReplayRequestContext context;
    private final boolean enabled;
    private final Duration delay;
    private final int maxRetries;

    public DbWritePreDmlRetryAspect(DbWriteReplayRequestContext context,
            @Value("${DB_WRITE_PRE_DML_RETRY_ENABLED:true}") boolean enabled,
            @Value("${DB_WRITE_PRE_DML_RETRY_DELAY:2s}") Duration delay,
            @Value("${DB_WRITE_PRE_DML_RETRY_MAX_RETRIES:2}") int maxRetries) {
        if (delay.isNegative()) throw new IllegalArgumentException("Pre-DML retry delay must not be negative");
        if (maxRetries < 0) throw new IllegalArgumentException("Pre-DML retry count must not be negative");
        this.context = context;
        this.enabled = enabled;
        this.delay = delay;
        this.maxRetries = maxRetries;
    }

    @Around("@annotation(com.stock_spoon.river_be.config.DbWritePreDmlRetryable)")
    public Object retryBeforeDml(ProceedingJoinPoint invocation) throws Throwable {
        if (!enabled || maxRetries == 0) return invocation.proceed();

        String handler = invocation.getSignature().toShortString();
        int retries = 0;
        while (true) {
            try {
                Object result = invocation.proceed();
                if (retries > 0) log.info("event=db_write_pre_dml_retry_succeeded handler={} retryCount={}", handler, retries);
                return result;
            } catch (Throwable failure) {
                SQLException connectionFailure =
                        DbMigrationWriteFallbackClassifier.findReadRetryableConnectionFailure(failure);
                // Rollback can replace the original JDBC failure. Do not use a stale
                // observed SQL error to retry unrelated validation/business exceptions.
                if (connectionFailure == null
                        && (failure instanceof TransactionException || failure instanceof DataAccessException)) {
                    SQLException observed = context.observedFallbackFailure();
                    if (DbMigrationWriteFallbackClassifier.isReadRetryableConnectionFailure(observed)) {
                        connectionFailure = observed;
                    }
                }
                if (connectionFailure == null) throw failure;

                String blockReason = TransactionSynchronizationManager.isActualTransactionActive()
                        ? "transaction_still_active" : context.preDmlRetryBlockReason();
                if (blockReason != null) {
                    log.info("event=db_write_pre_dml_retry_skipped handler={} reason={}", handler, blockReason);
                    throw failure;
                }
                if (retries >= maxRetries) {
                    log.warn("event=db_write_pre_dml_retry_exhausted handler={} retryCount={} sqlErrorCode={} sqlState={}",
                            handler, retries, connectionFailure.getErrorCode(), connectionFailure.getSQLState());
                    throw failure;
                }

                retries++;
                log.warn("event=db_write_pre_dml_retry_scheduled handler={} retry={} maxRetries={} delayMs={} "
                                + "sqlErrorCode={} sqlState={}",
                        handler, retries, maxRetries, delay.toMillis(),
                        connectionFailure.getErrorCode(), connectionFailure.getSQLState());
                try {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Request interrupted");
                    if (!delay.isZero()) TimeUnit.NANOSECONDS.sleep(delay.toNanos());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interrupted);
                    throw failure;
                }
                if (!context.resetForPreDmlRetry()) throw failure;
            }
        }
    }
}
