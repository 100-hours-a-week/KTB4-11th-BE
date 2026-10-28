package com.stock_spoon.river_be.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stock_spoon.river_be.user.repository.UserRepository;
import java.sql.SQLException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class JwtUserExistenceVerifierTests {
    @Test
    void retriesOnlyConnectionFailuresInFreshReadOnlyTransactions() {
        UserRepository users = mock(UserRepository.class);
        when(users.existsById(42L))
                .thenThrow(connectionFailure())
                .thenReturn(true);
        RecordingTransactionManager transactions = new RecordingTransactionManager();

        boolean exists = new JwtUserExistenceVerifier(users, transactions, 2, 0).exists(42L);

        assertThat(exists).isTrue();
        assertThat(transactions.definitions).hasSize(2)
                .allSatisfy(definition -> {
                    assertThat(definition.isReadOnly()).isTrue();
                    assertThat(definition.getPropagationBehavior())
                            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                });
    }

    @Test
    void retryOpensANewPhysicalConnectionForEachReadOnlyTransaction() throws Exception {
        UserRepository users = mock(UserRepository.class);
        when(users.existsById(42L)).thenThrow(connectionFailure()).thenReturn(true);
        DataSource dataSource = mock(DataSource.class);
        Connection firstConnection = mock(Connection.class);
        Connection secondConnection = mock(Connection.class);
        when(firstConnection.getAutoCommit()).thenReturn(true);
        when(secondConnection.getAutoCommit()).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(firstConnection, secondConnection);

        boolean exists = new JwtUserExistenceVerifier(
                users, new DataSourceTransactionManager(dataSource), 1, 0).exists(42L);

        assertThat(exists).isTrue();
        assertThat(firstConnection).isNotSameAs(secondConnection);
        verify(dataSource, times(2)).getConnection();
        verify(firstConnection).setReadOnly(true);
        verify(secondConnection).setReadOnly(true);
        verify(firstConnection).close();
        verify(secondConnection).close();
    }

    @Test
    void aSuccessfulMissingUserLookupReturnsFalseWithoutRetry() {
        UserRepository users = mock(UserRepository.class);
        when(users.existsById(42L)).thenReturn(false);
        RecordingTransactionManager transactions = new RecordingTransactionManager();

        assertThat(new JwtUserExistenceVerifier(users, transactions, 2, 0).exists(42L)).isFalse();
        assertThat(transactions.definitions).hasSize(1);
    }

    @Test
    void exhaustedConnectionRetriesBecomeAuthenticationServiceUnavailable() {
        UserRepository users = mock(UserRepository.class);
        when(users.existsById(42L)).thenThrow(connectionFailure());
        RecordingTransactionManager transactions = new RecordingTransactionManager();

        assertThatThrownBy(() -> new JwtUserExistenceVerifier(users, transactions, 2, 0).exists(42L))
                .isInstanceOf(AuthenticationServiceException.class)
                .hasMessage("Authentication user lookup is unavailable");
        assertThat(transactions.definitions).hasSize(3);
    }

    @Test
    void nonConnectionSqlErrorsAreNotRetriedOrConvertedToInvalidUser() {
        UserRepository users = mock(UserRepository.class);
        when(users.existsById(42L)).thenThrow(new DataAccessResourceFailureException(
                "query failed", new SQLException("query failed", "HY000", 1064)));
        RecordingTransactionManager transactions = new RecordingTransactionManager();

        assertThatThrownBy(() -> new JwtUserExistenceVerifier(users, transactions, 2, 0).exists(42L))
                .isInstanceOf(AuthenticationServiceException.class);
        assertThat(transactions.definitions).hasSize(1);
    }

    private static DataAccessResourceFailureException connectionFailure() {
        return new DataAccessResourceFailureException(
                "database connection was interrupted", new SQLException("connection reset", "08S01", 0));
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final List<TransactionDefinition> definitions = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            definitions.add(definition);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
