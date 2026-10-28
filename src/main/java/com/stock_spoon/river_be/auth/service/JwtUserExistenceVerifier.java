package com.stock_spoon.river_be.auth.service;

import com.stock_spoon.river_be.config.DbMigrationWriteFallbackClassifier;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Performs the JWT subject existence check in bounded, isolated read-only transactions. */
@Component
public class JwtUserExistenceVerifier {
    private static final Logger log = LoggerFactory.getLogger(JwtUserExistenceVerifier.class);
    private static final int MAX_CONFIGURED_RETRIES = 3;
    private static final long MAX_CONFIGURED_DELAY_MS = 250;

    private final UserRepository users;
    private final TransactionTemplate lookupTransaction;
    private final int maxRetries;
    private final long initialDelayMs;

    public JwtUserExistenceVerifier(UserRepository users, PlatformTransactionManager transactionManager,
            @Value("${JWT_AUTH_DB_MAX_RETRIES:2}") int maxRetries,
            @Value("${JWT_AUTH_DB_RETRY_DELAY_MS:50}") long initialDelayMs) {
        if (maxRetries < 0 || maxRetries > MAX_CONFIGURED_RETRIES) {
            throw new IllegalArgumentException("JWT_AUTH_DB_MAX_RETRIES must be between 0 and 3");
        }
        if (initialDelayMs < 0 || initialDelayMs > MAX_CONFIGURED_DELAY_MS) {
            throw new IllegalArgumentException("JWT_AUTH_DB_RETRY_DELAY_MS must be between 0 and 250");
        }
        this.users = users;
        this.maxRetries = maxRetries;
        this.initialDelayMs = initialDelayMs;
        this.lookupTransaction = new TransactionTemplate(transactionManager);
        this.lookupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lookupTransaction.setReadOnly(true);
        this.lookupTransaction.setTimeout(3);
    }

    /** Returns false only when the read succeeded and the subject does not exist. */
    public boolean exists(long userId) {
        long started = System.nanoTime();
        for (int attempt = 0; ; attempt++) {
            try {
                Boolean exists = lookupTransaction.execute(status -> users.existsById(userId));
                if (attempt > 0) {
                    log.info("event=jwt_auth_user_lookup_recovered requestId={} dispatcherType={} retryCount={} elapsedMs={}",
                            requestId(), dispatcherType(), attempt,
                            elapsedMs(started));
                }
                return Boolean.TRUE.equals(exists);
            } catch (RuntimeException error) {
                SQLException connectionFailure =
                        DbMigrationWriteFallbackClassifier.findReadRetryableConnectionFailure(error);
                if (connectionFailure == null) {
                    if (error instanceof DataAccessException || containsSqlException(error)) {
                        log.error("event=jwt_auth_user_lookup_unavailable requestId={} dispatcherType={} exceptionType={} sqlState={} sqlErrorCode={} elapsedMs={}",
                                requestId(), dispatcherType(), error.getClass().getSimpleName(), sqlState(error),
                                sqlErrorCode(error), elapsedMs(started));
                        throw new AuthenticationServiceException("Authentication user lookup is unavailable", error);
                    }
                    throw error;
                }

                if (attempt >= maxRetries) {
                    log.error("event=jwt_auth_user_lookup_exhausted requestId={} dispatcherType={} retryCount={} sqlState={} sqlErrorCode={} elapsedMs={}",
                            requestId(), dispatcherType(), attempt, connectionFailure.getSQLState(),
                            connectionFailure.getErrorCode(), elapsedMs(started));
                    throw new AuthenticationServiceException("Authentication user lookup is unavailable", error);
                }

                long delayMs = Math.min(MAX_CONFIGURED_DELAY_MS, initialDelayMs * (1L << attempt));
                log.warn("event=jwt_auth_user_lookup_retry requestId={} dispatcherType={} retry={} maxRetries={} delayMs={} sqlState={} sqlErrorCode={}",
                        requestId(), dispatcherType(), attempt + 1, maxRetries, delayMs, connectionFailure.getSQLState(),
                        connectionFailure.getErrorCode());
                try {
                    if (delayMs > 0) TimeUnit.MILLISECONDS.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    error.addSuppressed(interrupted);
                    throw new AuthenticationServiceException("Authentication user lookup was interrupted", error);
                }
            }
        }
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static boolean containsSqlException(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException) return true;
        }
        return false;
    }

    private static String sqlState(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) return sql.getSQLState();
        }
        return "none";
    }

    private static int sqlErrorCode(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) return sql.getErrorCode();
        }
        return 0;
    }

    private static String requestId() {
        String value = MDC.get("requestId");
        return value == null ? "missing" : value;
    }

    private static String dispatcherType() {
        String value = MDC.get("dispatcherType");
        return value == null ? "REQUEST" : value;
    }
}
