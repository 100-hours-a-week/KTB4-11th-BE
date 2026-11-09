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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Retries explicitly marked read handlers after a transient database connection failure.
 * The controller boundary is outside service transactions, so each retry starts fresh service transactions.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DbReadRetryAspect {
    private static final Logger log = LoggerFactory.getLogger(DbReadRetryAspect.class);

    private final boolean enabled;
    private final Duration delay;
    private final int maxRetries;

    public DbReadRetryAspect(
            @Value("${DB_READ_RETRY_ENABLED:true}") boolean enabled,
            @Value("${DB_READ_RETRY_DELAY:2s}") Duration delay,
            @Value("${DB_READ_RETRY_MAX_RETRIES:10}") int maxRetries) {
        if (delay.isNegative()) throw new IllegalArgumentException("db.read-retry.delay must not be negative");
        if (maxRetries < 0) throw new IllegalArgumentException("db.read-retry.max-retries must not be negative");
        this.enabled = enabled;
        this.delay = delay;
        this.maxRetries = maxRetries;
    }

    @Around("@annotation(com.stock_spoon.river_be.config.DbReadRetryable)")
    public Object retryRead(ProceedingJoinPoint invocation) throws Throwable {
        if (!enabled || maxRetries == 0) return invocation.proceed();

        String handler = invocation.getSignature().toShortString();
        int retries = 0;
        while (true) {
            try {
                Object result = invocation.proceed();
                if (retries > 0) {
                    log.info("event=db_read_retry_succeeded handler={} retryCount={}", handler, retries);
                }
                return result;
            } catch (Throwable error) {
                SQLException connectionFailure =
                        DbMigrationWriteFallbackClassifier.findReadRetryableConnectionFailure(error);
                if (connectionFailure == null) throw error;
                if (retries >= maxRetries) {
                    log.error("event=db_read_retry_exhausted handler={} retryCount={} sqlErrorCode={} sqlState={}",
                            handler, retries, connectionFailure.getErrorCode(), connectionFailure.getSQLState());
                    throw error;
                }

                retries++;
                log.warn("event=db_read_retry_scheduled handler={} retry={} maxRetries={} delayMs={} "
                                + "sqlErrorCode={} sqlState={}",
                        handler, retries, maxRetries, delay.toMillis(),
                        connectionFailure.getErrorCode(), connectionFailure.getSQLState());
                try {
                    if (!delay.isZero()) TimeUnit.NANOSECONDS.sleep(delay.toNanos());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    error.addSuppressed(interrupted);
                    throw error;
                }
            }
        }
    }
}
