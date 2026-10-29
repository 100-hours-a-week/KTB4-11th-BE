package com.stock_spoon.river_be.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.sql.PreparedStatement;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.json.JsonMapper;

class DbWriteReplayTransactionScopeTests {
    private final DbWriteReplayRequestContext requestContext = new DbWriteReplayRequestContext();

    @AfterEach
    void cleanUp() {
        requestContext.endRequest();
    }

    @Test
    void confirmedRollbackQueuesOnlyTheFailedTransactionAndLeavesPriorCommitAlone() throws Exception {
        requestContext.beginRequest("http-request-1", "write-1");
        DataSourceTransactionManager transactionManager = transactionManager();

        TransactionStatus committedTransaction =
                transactionManager.getTransaction(new DefaultTransactionDefinition());
        requestContext.recordSuccessfulWrite(
                statement("UPDATE stock SET price = ? WHERE id = ?"), false);
        transactionManager.commit(committedTransaction);

        SQLException fallbackError = readOnlyFailure();
        TransactionStatus failedTransaction =
                transactionManager.getTransaction(new DefaultTransactionDefinition());
        QueryInfo failedDml = new QueryInfo("UPDATE wallet SET balance = ? WHERE user_id = ?");
        failedDml.setParametersList(List.of(List.of(
                new ParameterSetOperation(
                        PreparedStatement.class.getMethod("setLong", int.class, long.class),
                        new Object[] {1, 500L}),
                new ParameterSetOperation(
                        PreparedStatement.class.getMethod("setLong", int.class, long.class),
                        new Object[] {2, 42L}))));
        ExecutionInfo failedExecution = new ExecutionInfo();
        failedExecution.setThrowable(fallbackError);
        new DbWriteReplayQueryListener(requestContext)
                .afterQuery(failedExecution, List.of(failedDml));
        transactionManager.rollback(failedTransaction);

        assertNull(requestContext.enqueueBlockReason(fallbackError));
        assertQueuedStatements(fallbackError, "http-request-1", List.of(
                "UPDATE wallet SET balance = ? WHERE user_id = ?"));
    }

    @Test
    void earlierDmlInTheSameRolledBackTransactionIsKeptWhenTheFallbackQueryIsNotDml()
            throws Exception {
        requestContext.beginRequest("http-request-2", "write-2");
        DataSourceTransactionManager transactionManager = transactionManager();

        TransactionStatus transaction =
                transactionManager.getTransaction(new DefaultTransactionDefinition());
        requestContext.recordSuccessfulWrite(
                statement("UPDATE stock SET price = ? WHERE id = ?"), false);
        SQLException fallbackError = readOnlyFailure();
        requestContext.observeFallbackFailure(fallbackError, List.of(), true, false);
        transactionManager.rollback(transaction);

        assertNull(requestContext.enqueueBlockReason(fallbackError));
        assertQueuedStatements(fallbackError, "http-request-2", List.of(
                "UPDATE stock SET price = ? WHERE id = ?"));
    }

    @Test
    void suppressedCommunicationsFailureWithUnconfirmedRollbackIsNotQueued() {
        requestContext.beginRequest("http-request-3", "write-3");
        TransactionExecution execution = mock(TransactionExecution.class);
        when(execution.isNewTransaction()).thenReturn(true);
        requestContext.beginTransaction(execution);
        requestContext.recordSuccessfulWrite(
                statement("UPDATE stock SET price = ? WHERE id = ?"), false);

        SQLException rollbackFailure = new SQLException("rollback failed", "HY000", 0);
        SQLException communicationsFailure = new SQLException(
                "Communications link failure", "08S01", 0);
        rollbackFailure.addSuppressed(communicationsFailure);
        requestContext.markTransactionRolledBack(execution, rollbackFailure);

        assertEquals(communicationsFailure,
                DbMigrationWriteFallbackClassifier.findWriteFallbackFailure(rollbackFailure));
        assertEquals("rollback_not_confirmed",
                requestContext.enqueueBlockReason(communicationsFailure));
    }

    @Test
    void fallbackWithoutDmlIsNotEligibleForQueueing() {
        requestContext.beginRequest("http-request-4", "write-4");
        DataSourceTransactionManager transactionManager = transactionManager();

        TransactionStatus transaction =
                transactionManager.getTransaction(new DefaultTransactionDefinition());
        SQLException fallbackError = readOnlyFailure();
        requestContext.observeFallbackFailure(fallbackError, List.of(), true, false);
        transactionManager.rollback(transaction);

        assertEquals("no_dml_before_fallback", requestContext.enqueueBlockReason(fallbackError));
    }

    @Test
    void dbReplayConfigurationRejectsTheBusinessOrderQueue() {
        DbWriteReplayProperties properties = new DbWriteReplayProperties();
        properties.setQueueUrl(
                "https://sqs.ap-northeast-2.amazonaws.com/123456789012/stockspoon-v2-prod-order.fifo");

        assertThrows(IllegalStateException.class,
                () -> new DbWriteReplayConfiguration().dbWriteReplaySqsClient(properties));
    }

    private DataSourceTransactionManager transactionManager() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        transactionManager.addListener(
                DbMigrationWriteTransactionAuditConfiguration.replayTransactionListener(requestContext));
        return transactionManager;
    }

    private void assertQueuedStatements(
            SQLException fallbackError, String httpRequestId, List<String> expectedSql) throws Exception {
        DbWriteReplayProperties properties = new DbWriteReplayProperties();
        properties.setQueueUrl("https://sqs.ap-northeast-2.amazonaws.com/123456789012/db-write-replay.fifo");
        SqsClient sqsClient = mock(SqsClient.class);
        when(sqsClient.sendMessage(any(SendMessageRequest.class))).thenReturn(
                SendMessageResponse.builder().messageId("message-1").build());
        JsonMapper objectMapper = JsonMapper.builder().build();
        DbWriteReplayExceptionResolver resolver = new DbWriteReplayExceptionResolver(
                properties, requestContext, sqsClient, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/writes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        resolver.resolveException(request, response, null, new RuntimeException(fallbackError));

        assertEquals(202, response.getStatus());
        ArgumentCaptor<SendMessageRequest> messageCaptor =
                ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(messageCaptor.capture());
        var payload = objectMapper.readTree(messageCaptor.getValue().messageBody());
        assertEquals(httpRequestId, payload.path("httpRequestId").asString());
        assertEquals(expectedSql.size(), payload.path("statements").size());
        for (int index = 0; index < expectedSql.size(); index++) {
            assertEquals(expectedSql.get(index),
                    payload.path("statements").get(index).path("sql").asString());
        }
        var parameters = payload.path("statements").get(0).path("parameterSets").get(0);
        if (parameters != null && parameters.size() > 0) {
            assertEquals("setLong", parameters.get(0).path("method").asString());
            assertEquals("500", parameters.get(0).path("args").get(1).path("value").asString());
        }
    }

    private static SQLException readOnlyFailure() {
        return new SQLException(
                "The MySQL server is running with the --read-only option",
                "HY000",
                DbMigrationWriteFallbackClassifier.MYSQL_READ_ONLY_SERVER);
    }

    private static Map<String, Object> statement(String sql) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("sql", sql);
        statement.put("parameterSets", List.<List<ParameterSetOperation>>of());
        return statement;
    }
}
