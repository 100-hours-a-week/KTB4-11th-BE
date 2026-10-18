package com.stock_spoon.river_be.dbwrite;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SqlWriteFailureClassifierTests {
    @Test
    void recognizesMysqlReadOnlyRejection() {
        var failure = new SQLException(
                "The MySQL server is running with the --super-read-only option so it cannot execute this statement",
                "HY000", 1290);

        assertThat(SqlWriteFailureClassifier.isWriterUnavailable(failure)).isTrue();
    }

    @Test
    void treatsProxySqlCodesAsUnconfirmedUntilConfiguredFromObservedLogs() {
        for (int code : new int[] {9001, 9002, 9004}) {
            assertThat(SqlWriteFailureClassifier.isWriterUnavailable(
                    new SQLException("ProxySQL writer unavailable", "HY000", code))).isFalse();
            assertThat(SqlWriteFailureClassifier.isWriterUnavailable(
                    new SQLException("observed ProxySQL error", "HY000", code), Set.of(code))).isTrue();
        }
    }

    @Test
    void doesNotQueueUnrelatedOrAmbiguousSqlFailures() {
        assertThat(SqlWriteFailureClassifier.isWriterUnavailable(
                new SQLException("Duplicate entry", "23000", 1062))).isFalse();
        assertThat(SqlWriteFailureClassifier.isWriterUnavailable(
                new SQLException("Connection was lost", "08S01", 2013))).isFalse();
        assertThat(SqlWriteFailureClassifier.isWriterUnavailable(
                new SQLException("The MySQL server is running with the --offline-mode option", "HY000", 1290)))
                .isFalse();
    }

    @Test
    void findsSqlFailureWrappedByTransactionExceptions() {
        var failure = new IllegalStateException("transaction rolled back",
                new SQLException("Database is read_only", "HY000", 1290));

        assertThat(SqlWriteFailureClassifier.isWriterUnavailable(failure)).isTrue();
    }
}
