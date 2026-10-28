package com.stock_spoon.river_be.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stock_spoon.river_be.auth.service.JwtUserExistenceVerifier;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class JwtUserExistenceAuthenticationFilterTests {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void transientLookupFailureRecoversBeforeGetAndPostHandlersRunOnce() throws Exception {
        var handlerCalls = new AtomicInteger();

        for (String method : List.of("GET", "POST")) {
            UserRepository users = mock(UserRepository.class);
            when(users.existsById(42L))
                    .thenThrow(connectionFailure())
                    .thenReturn(true);
            var transactions = new RecordingTransactionManager();
            var verifier = new JwtUserExistenceVerifier(users, transactions, 2, 0);
            var filter = new JwtUserExistenceAuthenticationFilter(verifier);
            authenticateUser();
            var request = new MockHttpServletRequest(method, "/protected");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> handlerCalls.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(transactions.definitions).hasSize(2).allSatisfy(definition -> {
                assertThat(definition.isReadOnly()).isTrue();
                assertThat(definition.getPropagationBehavior())
                        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            });
        }

        assertThat(handlerCalls).hasValue(2);
    }

    @Test
    void exhaustedLookupFailureReturns503BeforeProtectedHandlerRuns() throws Exception {
        UserRepository users = mock(UserRepository.class);
        when(users.existsById(42L)).thenThrow(connectionFailure());
        var transactions = new RecordingTransactionManager();
        var verifier = new JwtUserExistenceVerifier(users, transactions, 2, 0);
        var filter = new JwtUserExistenceAuthenticationFilter(verifier);
        var request = new MockHttpServletRequest("POST", "/protected");
        var response = new MockHttpServletResponse();
        var handlerCalls = new AtomicInteger();
        authenticateUser();

        filter.doFilter(request, response, (req, res) -> handlerCalls.incrementAndGet());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("AUTHENTICATION_DATABASE_UNAVAILABLE");
        assertThat(handlerCalls).hasValue(0);
        assertThat(transactions.definitions).hasSize(3);
    }

    private static void authenticateUser() {
        var jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject("42")
                .claim("actor", "USER")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
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
