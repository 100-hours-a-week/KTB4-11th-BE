package com.stock_spoon.river_be.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.stock_spoon.river_be.account.controller.AccountController;
import com.stock_spoon.river_be.account.dto.AccountCreateRequest;
import com.stock_spoon.river_be.account.dto.AccountNameUpdateRequest;
import com.stock_spoon.river_be.account.service.AccountHoldingsService;
import com.stock_spoon.river_be.account.service.AccountService;
import com.stock_spoon.river_be.competition.controller.CompetitionController;
import com.stock_spoon.river_be.competition.dto.CompetitionCreateRequest;
import com.stock_spoon.river_be.competition.service.CompetitionService;
import com.stock_spoon.river_be.execution.listener.OrderExecutionListener;
import com.stock_spoon.river_be.execution.service.OrderBuyPriceService;
import com.stock_spoon.river_be.execution.service.OrderExecutionService;
import com.stock_spoon.river_be.market.MarketStream;
import com.stock_spoon.river_be.order.controller.OrderController;
import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.service.OrderHistoryService;
import com.stock_spoon.river_be.order.service.OrderService;
import com.stock_spoon.river_be.order.service.OrderSubscriptionService;
import com.stock_spoon.river_be.order.validator.OrderMarketValidator;
import com.stock_spoon.river_be.watchlist.controller.WatchlistController;
import com.stock_spoon.river_be.watchlist.service.WatchlistService;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real MVC/AOP/JDBC/SDK path; only the SQS HTTP endpoint and SQL faults are local fixtures. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = DbWriteReplayHttpProducerTests.Config.class)
@TestPropertySource(properties = {
        "db-write-replay.enabled=true",
        "db-write-replay.queue-url=http://127.0.0.1:1/123456789012/producer-db-write-replay.fifo",
        "DB_READ_RETRY_DELAY=0s", "DB_READ_RETRY_MAX_RETRIES=1"
})
class DbWriteReplayHttpProducerTests {
    private static final String SELECT = "SELECT amount FROM counters WHERE id = ? FOR UPDATE";
    private static final String UPDATE = "UPDATE counters SET amount = amount + 1 WHERE id = ?";
    private static final String ACCOUNT_BODY = "{\"account_name\":\"migration-test\",\"initial_capital\":1000000}";
    @Autowired WebApplicationContext web;
    @Autowired DbWriteReplayProperties properties;
    @Autowired ControllerMocks services;
    @Autowired FaultyDatabase database;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSourceTransactionManager transactions;
    @Autowired JsonMapper json;
    @Autowired DataSource dataSource;
    @MockitoBean(name = "dbWriteReplaySqsClient") SqsClient sqs;
    private final List<JsonNode> wireRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger serviceAttempts = new AtomicInteger();
    private HttpServer server;
    private SqsClient wireClient;
    private MockMvc mvc;
    private int sqsStatus;

    @BeforeEach
    void setUp() throws Exception {
        assertInstanceOf(ProxyDataSource.class, dataSource); // actual production BeanPostProcessor
        database.mode = Mode.SELECT_READ_ONLY;
        new JdbcTemplate(database.real).update("UPDATE counters SET amount = 0 WHERE id = 1");
        services.reset();
        serviceAttempts.set(0);
        wireRequests.clear();
        sqsStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                JsonNode request = json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                wireRequests.add(request);
                String response;
                if (sqsStatus == 200) {
                    String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                            .digest(request.path("MessageBody").asString().getBytes(StandardCharsets.UTF_8)));
                    response = json.writeValueAsString(Map.of("MessageId", UUID.randomUUID().toString(), "MD5OfMessageBody", md5));
                } else {
                    response = "{\"__type\":\"com.amazonaws.sqs#AccessDeniedException\",\"message\":\"fixture denied\"}";
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/x-amz-json-1.0");
                exchange.sendResponseHeaders(sqsStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            } finally {
                exchange.close();
            }
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.setQueueUrl(endpoint + "/123456789012/producer-db-write-replay.fifo");
        wireClient = SqsClient.builder().region(Region.AP_NORTHEAST_2).endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .overrideConfiguration(configuration -> configuration.retryPolicy(RetryPolicy.none())
                        .apiCallTimeout(Duration.ofSeconds(5))).build();
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenAnswer(call -> wireClient.sendMessage(call.getArgument(0, SendMessageRequest.class)));

        when(services.accounts.create(anyLong(), any(AccountCreateRequest.class))).thenAnswer(call -> { write(); return null; });
        when(services.accounts.rename(anyLong(), anyLong(), any(AccountNameUpdateRequest.class))).thenAnswer(call -> { write(); return null; });
        when(services.watchlists.add(anyLong(), anyString())).thenAnswer(call -> { write(); return true; });
        doAnswer(call -> { write(); return null; }).when(services.watchlists).remove(anyLong(), anyString());
        when(services.orders.reserveLimit(anyLong(), anyLong(), any(OrderCreateRequest.class), any(Order.Source.class)))
                .thenAnswer(call -> { write(); return null; });
        doAnswer(call -> { write(); return null; }).when(services.orders).cancel(anyLong(), anyLong(), anyLong());
        when(services.subscriptions.create(anyString(), any())).thenAnswer(call -> {
            Supplier<?> save = call.getArgument(1);
            return save.get();
        });
        when(services.competitions.createCompetition(anyLong(), any(CompetitionCreateRequest.class)))
                .thenAnswer(call -> { write(); return null; });
        when(services.accounts.list(anyLong())).thenAnswer(call -> {
            serviceAttempts.incrementAndGet();
            new TransactionTemplate(transactions).execute(status -> jdbc.queryForObject(SELECT, Integer.class, 1));
            return List.of();
        });
        Jwt jwt = Jwt.withTokenValue("never-store-this-token").header("alg", "none")
                .subject("42").claim("actor", "USER").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        mvc = MockMvcBuilders.webAppContextSetup(web)
                .addFilters(new RequestLoggingFilter(), web.getBean(DbWriteReplayRequestFilter.class)).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (wireClient != null) wireClient.close();
        if (server != null) server.stop(0);
    }

    @ParameterizedTest
    @EnumSource(DbWriteReplayBusinessCommand.Type.class)
    void lockingSelectReadOnlyFailureReachesSqsForEveryLoadTestWrite(DbWriteReplayBusinessCommand.Type type) throws Exception {
        MvcResult result = mvc.perform(request(type).header("Idempotency-Key", "never-store-raw-key")
                .header("Authorization", "Bearer never-store-this-token")).andReturn();

        assertEquals(202, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(1, serviceAttempts.get(), "write handlers must never be retried");
        assertEquals(1, wireRequests.size(), "SDK must actually send an HTTP request");
        JsonNode sent = wireRequests.get(0);
        assertEquals(properties.getQueueUrl(), sent.path("QueueUrl").asString());
        JsonNode message = json.readTree(sent.path("MessageBody").asString());
        assertEquals("BUSINESS_COMMAND", message.path("operation").asString());
        assertEquals(type.name(), message.path("businessCommand").path("type").asString());
        assertEquals(42, message.path("businessCommand").path("actor").path("userId").asLong());
        assertEquals("NO_DML_OBSERVED", message.path("replaySafety").asString());
        assertEquals(0, message.path("capturedStatements").size());
        assertEquals(message.path("requestId").asString(), sent.path("MessageDeduplicationId").asString());
        assertEquals("db-write-replay", sent.path("MessageGroupId").asString());
        assertFalse(sent.path("MessageBody").asString().contains("never-store"));
        assertEquals(0, persistedAmount());
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"SELECT_CONNECTION_LOSS", "ACQUIRE_TIMEOUT", "DML_READ_ONLY", "DML_CONNECTION_LOSS", "COMMIT_CONNECTION_LOSS", "COMMIT_ACK_LOST_AFTER_COMMIT", "POST_COMMIT_READ_FAILURE", "PRIOR_COMMIT"})
    void connectionAndDmlFailuresArePreservedWithoutRetryingTheWrite(Mode mode) throws Exception {
        database.mode = mode;
        MvcResult result = mvc.perform(request(DbWriteReplayBusinessCommand.Type.ACCOUNT_CREATE)).andReturn();
        assertEquals(202, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(1, serviceAttempts.get());
        assertEquals(1, wireRequests.size());
        JsonNode message = json.readTree(wireRequests.get(0).path("MessageBody").asString());
        boolean uncertain = mode == Mode.DML_CONNECTION_LOSS || mode == Mode.COMMIT_CONNECTION_LOSS
                || mode == Mode.COMMIT_ACK_LOST_AFTER_COMMIT || mode == Mode.POST_COMMIT_READ_FAILURE
                || mode == Mode.PRIOR_COMMIT;
        assertEquals(uncertain ? "RECONCILIATION_REQUIRED" : "REEXECUTE_COMMAND", message.path("replayPolicy").asString());
        if (mode == Mode.COMMIT_ACK_LOST_AFTER_COMMIT || mode == Mode.POST_COMMIT_READ_FAILURE) {
            assertEquals(1, persistedAmount(), "the original business write really committed");
            assertEquals("UNCONFIRMED_OR_PARTIAL", message.path("replaySafety").asString());
        }
    }

    @Test
    void successfulWriteDoesNotPublishAnything() throws Exception {
        database.mode = Mode.HEALTHY;
        assertEquals(201, mvc.perform(request(DbWriteReplayBusinessCommand.Type.ACCOUNT_CREATE)).andReturn().getResponse().getStatus());
        assertEquals(1, serviceAttempts.get());
        assertEquals(1, persistedAmount());
        assertTrue(wireRequests.isEmpty());
        verifyNoInteractions(sqs);
    }

    @Test
    void sqsRejectionDoesNotReturnAccepted() throws Exception {
        sqsStatus = 403;
        assertEquals(503, mvc.perform(request(DbWriteReplayBusinessCommand.Type.ACCOUNT_CREATE)).andReturn().getResponse().getStatus());
        assertEquals(1, serviceAttempts.get());
        assertEquals(1, wireRequests.size());
    }

    @Test
    void businessRejectionDoesNotEnterTheMigrationQueue() throws Exception {
        when(services.accounts.create(anyLong(), any(AccountCreateRequest.class))).thenThrow(new IllegalArgumentException("business rejected"));
        assertEquals(400, mvc.perform(request(DbWriteReplayBusinessCommand.Type.ACCOUNT_CREATE)).andReturn().getResponse().getStatus());
        assertTrue(wireRequests.isEmpty());
        verifyNoInteractions(sqs);
    }

    @Test
    void constraintViolationDoesNotEnterTheMigrationQueue() throws Exception {
        database.mode = Mode.CONSTRAINT;
        assertEquals(503, mvc.perform(request(DbWriteReplayBusinessCommand.Type.ACCOUNT_CREATE)).andReturn().getResponse().getStatus());
        assertTrue(wireRequests.isEmpty());
        verifyNoInteractions(sqs);
    }

    @Test
    void readRequestsRetryOnlyTheReadAndNeverPublish() throws Exception {
        database.mode = Mode.SELECT_CONNECTION_LOSS;
        assertEquals(503, mvc.perform(get("/api/v1/users/me/accounts")).andReturn().getResponse().getStatus());
        assertEquals(2, serviceAttempts.get());
        assertTrue(wireRequests.isEmpty());
        verifyNoInteractions(sqs);
    }

    @Test
    void stableClientKeyAndPayloadHashSurviveNewHttpRequests() throws Exception {
        for (String name : List.of("first", "first", "changed")) {
            assertEquals(202, mvc.perform(post("/api/v1/users/me/accounts").contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "stable-client-key")
                    .content(ACCOUNT_BODY.replace("migration-test", name))).andReturn().getResponse().getStatus());
        }
        List<JsonNode> messages = wireRequests.stream().map(request -> json.readTree(request.path("MessageBody").asString())).toList();
        assertEquals(3, messages.size());
        assertEquals(messages.get(0).path("idempotencyKey"), messages.get(1).path("idempotencyKey"));
        assertEquals(messages.get(0).path("idempotencyKey"), messages.get(2).path("idempotencyKey"));
        assertEquals(messages.get(0).path("payloadHash"), messages.get(1).path("payloadHash"));
        assertNotEquals(messages.get(0).path("payloadHash"), messages.get(2).path("payloadHash"));
    }

    private MockHttpServletRequestBuilder request(DbWriteReplayBusinessCommand.Type type) {
        return switch (type) {
            case ACCOUNT_CREATE -> post("/api/v1/users/me/accounts").contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY);
            case ACCOUNT_RENAME -> patch("/api/v1/users/me/accounts/9").contentType(MediaType.APPLICATION_JSON).content("{\"account_name\":\"renamed\"}");
            case WATCHLIST_ADD -> post("/api/v2/users/me/watchlist").contentType(MediaType.APPLICATION_JSON).content("{\"stock_code\":\"005930\"}");
            case WATCHLIST_REMOVE -> delete("/api/v2/users/me/watchlist/005930");
            case ORDER_CREATE -> post("/api/v1/accounts/9/orders").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"stock_code\":\"005930\",\"order_side\":\"buy\",\"order_type\":\"limit\",\"limit_price\":1,\"quantity\":1}");
            case ORDER_CANCEL -> patch("/api/v1/accounts/9/orders/10").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"cancelled\"}");
            case COMPETITION_CREATE -> post("/api/v2/users/me/competitions").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"competition_name\":\"migration\",\"competition_type\":\"return_rate\",\"start_date\":\"2026-11-02\",\"end_date\":\"2026-11-05\",\"initial_capital\":1000000}");
        };
    }

    private void write() {
        serviceAttempts.incrementAndGet();
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        if (database.mode == Mode.PRIOR_COMMIT) transaction.executeWithoutResult(status -> jdbc.update(UPDATE, 1));
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject(SELECT, Integer.class, 1);
            jdbc.update(UPDATE, 1);
        });
        if (database.mode == Mode.POST_COMMIT_READ_FAILURE) {
            transaction.setReadOnly(true);
            transaction.executeWithoutResult(status -> jdbc.queryForObject(SELECT, Integer.class, 1));
        }
    }

    private int persistedAmount() {
        return new JdbcTemplate(database.real).queryForObject("SELECT amount FROM counters WHERE id = 1", Integer.class);
    }

    enum Mode { HEALTHY, SELECT_READ_ONLY, SELECT_CONNECTION_LOSS, ACQUIRE_TIMEOUT, DML_READ_ONLY, DML_CONNECTION_LOSS, COMMIT_CONNECTION_LOSS, COMMIT_ACK_LOST_AFTER_COMMIT, POST_COMMIT_READ_FAILURE, PRIOR_COMMIT, CONSTRAINT }

    static class FaultyDatabase {
        volatile Mode mode;
        final DataSource real = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");

        FaultyDatabase() {
            new JdbcTemplate(real).execute("CREATE TABLE counters (id INT PRIMARY KEY, amount INT NOT NULL)");
            new JdbcTemplate(real).update("INSERT INTO counters VALUES (1, 0)");
        }

        DataSource dataSource() {
            return new DelegatingDataSource(real) {
                @Override
                public Connection getConnection() throws SQLException {
                    if (mode == Mode.ACQUIRE_TIMEOUT) throw new SQLException("ProxySQL timeout", "HY000", 9001);
                    Connection connection = super.getConnection();
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("rollback") && connection.isClosed()) {
                                    // Match the Hikari error that replaces the original 08S01 in production.
                                    throw new SQLException("Connection is closed");
                                }
                                if (method.getName().equals("commit") && mode == Mode.COMMIT_CONNECTION_LOSS) {
                                    connection.close();
                                    throw new SQLException("Commit response lost", "08S01", 0);
                                }
                                if (method.getName().equals("commit") && mode == Mode.COMMIT_ACK_LOST_AFTER_COMMIT) {
                                    invoke(connection, method, args);
                                    connection.close();
                                    throw new SQLException("Committed but response lost", "08S01", 0);
                                }
                                Object result = invoke(connection, method, args);
                                if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement) {
                                    return wrapStatement(connection, statement, (String) args[0]);
                                }
                                return result;
                            });
                }
            };
        }

        private Object wrapStatement(Connection connection, PreparedStatement statement, String sql) {
            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                    (proxy, method, args) -> {
                        boolean select = sql.equals(SELECT) && method.getName().equals("executeQuery");
                        boolean update = sql.equals(UPDATE) && method.getName().equals("executeUpdate");
                        if (select && mode == Mode.POST_COMMIT_READ_FAILURE
                                && new JdbcTemplate(real).queryForObject("SELECT amount FROM counters WHERE id = 1", Integer.class) == 1) {
                            throw new SQLException("Communications link failure", "08S01", 0);
                        }
                        if ((select && (mode == Mode.SELECT_READ_ONLY || mode == Mode.PRIOR_COMMIT))
                                || (update && mode == Mode.DML_READ_ONLY)) {
                            throw new SQLException("The MySQL server is running with the --read-only option", "HY000", 1290);
                        }
                        if ((select && mode == Mode.SELECT_CONNECTION_LOSS) || (update && mode == Mode.DML_CONNECTION_LOSS)) {
                            connection.close();
                            throw new SQLException("Communications link failure", "08S01", 0);
                        }
                        if (update && mode == Mode.CONSTRAINT) throw new SQLException("Duplicate key", "23000", 1062);
                        return invoke(statement, method, args);
                    });
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
        }
    }

    static class ControllerMocks {
        final AccountService accounts = mock(AccountService.class);
        final WatchlistService watchlists = mock(WatchlistService.class);
        final OrderService orders = mock(OrderService.class);
        final OrderSubscriptionService subscriptions = mock(OrderSubscriptionService.class);
        final CompetitionService competitions = mock(CompetitionService.class);

        void reset() { org.mockito.Mockito.reset(accounts, watchlists, orders, subscriptions, competitions); }
    }

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(Exception.class)
        ResponseEntity<Map<String, String>> failure(Exception failure) {
            return ResponseEntity.status(failure instanceof IllegalArgumentException ? 400 : 503).body(Map.of("status", "failed", "type", failure.getClass().getSimpleName(), "detail", String.valueOf(failure.getMessage())));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({DbWriteReplayConfiguration.class, DbWriteReplayRequestContext.class, DbWriteReplayBusinessCommandAspect.class,
            DbWriteReplayRequestFilter.class, DbMigrationWriteTransactionAuditConfiguration.class, DbReadRetryAspect.class})
    static class Config implements WebMvcConfigurer {
        @Bean static org.springframework.core.convert.ConversionService conversionService() {
            return org.springframework.boot.convert.ApplicationConversionService.getSharedInstance();
        }
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
        @Bean ControllerMocks services() { return new ControllerMocks(); }
        @Bean FaultyDatabase faultyDatabase() { return new FaultyDatabase(); }
        @Bean DataSource dataSource(FaultyDatabase database) { return database.dataSource(); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean DataSourceTransactionManager transactions(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean Errors errors() { return new Errors(); }
        @Bean AccountController accounts(ControllerMocks mocks) {
            return new AccountController(mocks.accounts, mock(AccountHoldingsService.class),
                    mock(com.stock_spoon.river_be.account.service.AccountDeletionService.class));
        }
        @Bean WatchlistController watchlists(ControllerMocks mocks) { return new WatchlistController(mocks.watchlists); }
        @Bean CompetitionController competitions(ControllerMocks mocks) { return new CompetitionController(mocks.competitions); }
        @Bean OrderController orders(ControllerMocks mocks) {
            return new OrderController(mocks.orders, mock(OrderMarketValidator.class), mocks.subscriptions,
                    mock(OrderExecutionListener.class), mock(OrderHistoryService.class), mock(OrderExecutionService.class),
                    mock(OrderBuyPriceService.class), mock(MarketStream.class));
        }
        @Override public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new AuthenticationPrincipalArgumentResolver());
        }
    }
}
