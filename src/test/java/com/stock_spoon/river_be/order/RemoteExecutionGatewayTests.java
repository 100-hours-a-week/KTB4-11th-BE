package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.order.client.RemoteExecutionGateway;
import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RemoteExecutionGatewayTests {
    private OrderCreateRequest request() {
        return new OrderCreateRequest("005930", "buy", "market", null, 1L, "test", null,
                java.util.List.of(), 30.0, false);
    }
    @Test
    void gatewayTimeoutChecksSameRequestIdAndReturnsCommittedResult() throws Exception {
        var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = server(received, 200);
        try {
            var gateway = new RemoteExecutionGateway("http://localhost:" + server.getAddress().getPort(), "test-key");
            assertThat(gateway.market(1, 2, request())).isEqualTo(123);
            assertThat(received).hasSize(2);
            assertThat(received.get(0)).isEqualTo(received.get(1));
        } finally { server.stop(0); }
    }
    @Test
    void unknownResultIsNotReportedAsDefiniteFailureAndIncludesRequestId() throws Exception {
        var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = server(received, 404);
        try {
            var gateway = new RemoteExecutionGateway("http://localhost:" + server.getAddress().getPort(), "test-key");
            assertThatThrownBy(() -> gateway.market(1, 2, request())).isInstanceOfSatisfying(OrderException.class, error -> {
                assertThat(error.code()).isEqualTo("EXECUTION_RESULT_UNKNOWN");
                var id = tools.jackson.databind.json.JsonMapper.builder().build().readTree(received.get(0)).path("requestId").asText();
                assertThat(error.getMessage()).contains(id);
            });
        } finally { server.stop(0); }
    }
    private HttpServer server(java.util.List<String> received, int resultStatus) throws java.io.IOException {
        var server = HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/execution/", exchange -> {
            try (exchange) {
                received.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                boolean market = exchange.getRequestURI().getPath().endsWith("/market");
                byte[] body = (market ? "{\"message\":\"upstream timeout\"}" : "{\"orderId\":123}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(market ? 504 : resultStatus, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        return server;
    }
}
