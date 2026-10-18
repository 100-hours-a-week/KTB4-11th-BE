package com.stock_spoon.river_be.dbwrite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class DbWriteReplayServiceTests {
    private static final String QUEUE_URL = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/db-write-replay.fifo";

    @Mock
    private SqsClient sqs;

    @AfterEach
    void cleanTransactionContext() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        MDC.clear();
    }

    @Test
    void enqueuesOrderedSqlAndBindOperationsForRolledBackWriterFailure() throws Exception {
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(
                SendMessageResponse.builder().messageId("sqs-message-1").build());
        var service = new DbWriteReplayService(sqs, true, QUEUE_URL, "");
        MDC.put("requestId", "http-request-1");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        service.afterQuery(new ExecutionInfo(), List.of(query(
                "insert into orders (account_id, stock_code) values (?, ?)",
                "setLong", new Object[] {1, 42L}, "setString", new Object[] {2, "005930"})));
        var error = new SQLException("The MySQL server is running with the --super-read-only option", "HY000", 1290);
        var execution = new ExecutionInfo();
        execution.setThrowable(error);
        service.afterQuery(execution, List.of(query(
                "update accounts set balance = ? where id = ?",
                "setBigDecimal", new Object[] {1, new java.math.BigDecimal("100.25")},
                "setLong", new Object[] {1, 7L})));

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        var replayRequestId = service.enqueueIfWriterUnavailable(error,
                DbWriteReplayService.Operation.ORDER_CREATE, null);

        assertThat(replayRequestId).isPresent();
        var sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(sent.capture());
        assertThat(sent.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(sent.getValue().messageGroupId()).isEqualTo("db-write-replay-global");
        assertThat(sent.getValue().messageDeduplicationId()).isEqualTo(replayRequestId.get());

        var body = JsonMapper.builder().build().readTree(sent.getValue().messageBody());
        assertThat(body.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(body.path("requestId").asText()).isEqualTo(replayRequestId.get());
        assertThat(body.path("correlationId").asText()).isEqualTo("http-request-1");
        assertThat(body.path("operation").asText()).isEqualTo("SQL_TRANSACTION");
        assertThat(body.path("statements").size()).isEqualTo(2);
        assertThat(body.path("statements").get(0).path("sql").asText())
                .isEqualTo("insert into orders (account_id, stock_code) values (?, ?)");
        assertThat(body.path("statements").get(0).path("parameterSets").get(0).get(0).path("method").asText())
                .isEqualTo("setLong");
        assertThat(body.path("statements").get(0).path("parameterSets").get(0).get(1).path("args").get(1).path("value").asText())
                .isEqualTo("005930");
        assertThat(body.path("statements").get(1).path("parameterSets").get(0).get(0).path("args").get(1).path("type").asText())
                .isEqualTo("java.math.BigDecimal");
    }

    @Test
    void doesNotQueueAWriteFailureThatWasNotRolledBackForWriterUnavailability() throws Exception {
        var service = new DbWriteReplayService(sqs, true, QUEUE_URL, "");
        MDC.put("requestId", "http-request-2");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        var nonWriterFailure = new SQLException("Duplicate entry", "23000", 1062);
        var execution = new ExecutionInfo();
        execution.setThrowable(nonWriterFailure);
        service.afterQuery(execution, List.of(query("insert into accounts (name) values (?)",
                "setString", new Object[] {1, "test"})));

        TransactionSynchronizationManager.getSynchronizations().forEach(
                sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(service.enqueueIfWriterUnavailable(nonWriterFailure,
                DbWriteReplayService.Operation.ACCOUNT_CREATE, null)).isEmpty();
        verify(sqs, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void doesNotQueueWhenBindValueCannotBeSerialized() throws Exception {
        var service = new DbWriteReplayService(sqs, true, QUEUE_URL, "");
        MDC.put("requestId", "http-request-3");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        var writerFailure = new SQLException("read_only", "HY000", 1290);
        var execution = new ExecutionInfo();
        execution.setThrowable(writerFailure);
        service.afterQuery(execution, List.of(query("insert into uploads (data) values (?)",
                "setBinaryStream", new Object[] {1, new java.io.ByteArrayInputStream(new byte[] {1})})));

        TransactionSynchronizationManager.getSynchronizations().forEach(
                sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThatThrownBy(() -> service.enqueueIfWriterUnavailable(writerFailure,
                DbWriteReplayService.Operation.ORDER_CREATE, null))
                .isInstanceOf(DbWriteReplayEnqueueException.class);
        verify(sqs, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void leavesTheDatabaseErrorUnqueuedWhenFeatureIsDisabled() {
        var service = new DbWriteReplayService(sqs, false, "", "");

        var requestId = service.enqueueIfWriterUnavailable(
                new SQLException("read_only", "HY000", 1290),
                DbWriteReplayService.Operation.ORDER_CANCEL,
                null);

        assertThat(requestId).isEmpty();
        verify(sqs, never()).sendMessage(any(SendMessageRequest.class));
    }

    private static QueryInfo query(String sql, Object... methodAndArgs) throws Exception {
        var operations = new java.util.ArrayList<ParameterSetOperation>();
        for (int index = 0; index < methodAndArgs.length; index += 2) {
            String methodName = (String) methodAndArgs[index];
            Object[] args = (Object[]) methodAndArgs[index + 1];
            Method method = java.util.Arrays.stream(PreparedStatement.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .filter(candidate -> candidate.getParameterCount() == args.length)
                    .findFirst().orElseThrow();
            operations.add(new ParameterSetOperation(method, args));
        }
        var query = new QueryInfo(sql);
        query.setParametersList(List.of(operations));
        return query;
    }
}
