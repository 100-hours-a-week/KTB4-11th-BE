package com.stock_spoon.river_be.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.json.JsonMapper;

class DbWriteReplaySqlProducerTests {
    private static final String SELECT = "SELECT amount FROM counters WHERE id = ?";
    private static final String UPDATE = "UPDATE counters SET amount = amount + 1 WHERE id = ?";
    private final DbWriteReplayRequestContext context = new DbWriteReplayRequestContext();
    private final DbWriteReplayQueryListener listener = new DbWriteReplayQueryListener(context);
    private final SqsClient sqs = mock(SqsClient.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final DbWriteReplayExceptionResolver resolver;

    DbWriteReplaySqlProducerTests() {
        DbWriteReplayProperties properties = new DbWriteReplayProperties();
        properties.setQueueUrl("https://sqs.ap-northeast-2.amazonaws.com/123456789012/db-write-replay.fifo");
        resolver = new DbWriteReplayExceptionResolver(properties, context, sqs, json);
    }

    @BeforeEach
    void beginRequest() {
        context.beginRequest("http-request-1", "audit-1");
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(
                SendMessageResponse.builder().messageId("message-1").build());
    }

    @AfterEach
    void cleanUp() {
        context.endRequest();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void dmlFailurePublishesItsSqlAndBindingsToTheLambdaContract() throws Exception {
        Fixture fixture = new Fixture(Mode.DML_READ_ONLY);
        Exception failure = assertThrows(Exception.class, () -> fixture.handler.write());
        assertEquals(1, fixture.handler.calls.get());
        assertEquals(0, fixture.persistedAmount());

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertNotNull(resolve(failure, response));
        assertEquals(202, response.getStatus());
        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(sent.capture());
        var payload = json.readTree(sent.getValue().messageBody());
        assertEquals(2, payload.path("schemaVersion").asInt());
        assertEquals("SQL_TRANSACTION", payload.path("operation").asString());
        assertEquals("http-request-1", payload.path("httpRequestId").asString());
        assertEquals(1, payload.path("statements").size());
        assertEquals(UPDATE, payload.path("statements").get(0).path("sql").asString());
        var bind = payload.path("statements").get(0).path("parameterSets").get(0).get(0);
        assertEquals("setObject", bind.path("method").asString());
        assertEquals("1", bind.path("args").get(1).path("value").asString());
        assertFalse(payload.has("businessCommand"));
        assertFalse(payload.has("idempotencyKey"));
        assertFalse(sent.getValue().messageBody().contains("BUSINESS_COMMAND"));
        assertEquals(payload.path("requestId").asString(), sent.getValue().messageDeduplicationId());
    }

    @Test
    void dmlReadOnlyFailureDoesNotRetryTheHandlerAndQueuesSqlAfterConfirmedRollback() {
        Fixture fixture = new Fixture(Mode.DML_READ_ONLY);
        Exception failure = assertThrows(Exception.class, () -> fixture.handler.write());
        assertEquals(1, fixture.handler.calls.get());
        assertNotNull(resolve(failure, new MockHttpServletResponse()));
        verify(sqs).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void dmlWithLostResponseAndUnconfirmedRollbackIsNeitherRetriedNorQueued() {
        Fixture fixture = new Fixture(Mode.DML_CONNECTION_LOSS);
        Exception failure = assertThrows(Exception.class, () -> fixture.handler.write());
        assertEquals(1, fixture.handler.calls.get());
        assertNull(resolve(failure, new MockHttpServletResponse()));
        verify(sqs, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void uncertainSqsSendReusesTheSqlTransportIdentity() throws Exception {
        Fixture fixture = new Fixture(Mode.DML_READ_ONLY);
        Exception failure = assertThrows(Exception.class, () -> fixture.handler.write());
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(new IllegalStateException("SendMessage response lost"))
                .thenReturn(SendMessageResponse.builder().messageId("message-1").build());
        assertNull(resolve(failure, new MockHttpServletResponse()));
        assertNotNull(resolve(failure, new MockHttpServletResponse()));
        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs, times(2)).sendMessage(sent.capture());
        assertEquals(sent.getAllValues().get(0).messageBody(), sent.getAllValues().get(1).messageBody());
        assertEquals(sent.getAllValues().get(0).messageDeduplicationId(),
                sent.getAllValues().get(1).messageDeduplicationId());
    }

    private Object resolve(Exception failure, MockHttpServletResponse response) {
        return resolver.resolveException(new MockHttpServletRequest("POST", "/writes"), response, null, failure);
    }

    enum Mode { HEALTHY, READ_CONNECTION_LOSS, BEGIN_CONNECTION_LOSS, DML_CONNECTION_LOSS, DML_READ_ONLY }

    class Fixture {
        final AtomicInteger connections = new AtomicInteger();
        final DataSource real = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1", "sa", "");
        final Handler handler;

        Fixture(Mode... failures) {
            JdbcTemplate setup = new JdbcTemplate(real);
            setup.execute("CREATE TABLE counters (id INT PRIMARY KEY, amount INT NOT NULL)");
            setup.update("INSERT INTO counters VALUES (1, 0)");
            DataSource faulty = new DelegatingDataSource(real) {
                @Override
                public Connection getConnection() throws SQLException {
                    int number = connections.getAndIncrement();
                    Mode mode = number < failures.length ? failures[number] : Mode.HEALTHY;
                    if (mode == Mode.BEGIN_CONNECTION_LOSS) throw communicationsFailure();
                    Connection actual = super.getConnection();
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                                Object result = invoke(actual, method, args);
                                if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement prepared) {
                                    String sql = (String) args[0];
                                    return statement(actual, prepared, sql, mode);
                                }
                                return result;
                            });
                }
            };
            DataSource observed = ProxyDataSourceBuilder.create(faulty).listener(listener).build();
            DataSourceTransactionManager manager = new DataSourceTransactionManager(observed);
            manager.addListener(DbMigrationWriteTransactionAuditConfiguration.replayTransactionListener(context));
            handler = new Handler(new JdbcTemplate(observed), new TransactionTemplate(manager));
        }

        int persistedAmount() {
            return new JdbcTemplate(real).queryForObject("SELECT amount FROM counters WHERE id = 1", Integer.class);
        }
    }

    private static Object statement(Connection connection, PreparedStatement target, String sql, Mode mode) {
        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> {
                    boolean select = sql.equals(SELECT) && method.getName().equals("executeQuery");
                    boolean update = sql.equals(UPDATE) && method.getName().equals("executeUpdate");
                    if (update && mode == Mode.DML_READ_ONLY) {
                        throw new SQLException("The MySQL server is running with the --read-only option", "HY000", 1290);
                    }
                    if ((select && mode == Mode.READ_CONNECTION_LOSS) || (update && mode == Mode.DML_CONNECTION_LOSS)) {
                        connection.close(); // The following real rollback must fail too.
                        throw communicationsFailure();
                    }
                    return invoke(target, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }

    private static SQLException communicationsFailure() {
        return new SQLException("Communications link failure", "08S01", 0);
    }

    public static class Handler {
        final AtomicInteger calls = new AtomicInteger();
        private final JdbcTemplate jdbc;
        private final TransactionTemplate transaction;

        Handler(JdbcTemplate jdbc, TransactionTemplate transaction) {
            this.jdbc = jdbc;
            this.transaction = transaction;
        }

        public int write() {
            calls.incrementAndGet();
            return transaction.execute(status -> {
                jdbc.queryForObject(SELECT, Integer.class, 1);
                return jdbc.update(UPDATE, 1);
            });
        }

    }
}
