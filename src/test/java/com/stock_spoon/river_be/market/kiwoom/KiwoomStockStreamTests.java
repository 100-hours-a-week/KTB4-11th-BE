package com.stock_spoon.river_be.market.kiwoom;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KiwoomStockStreamTests {
    private static final Instant NOW = Instant.parse("2026-09-28T01:00:00Z");
    private final KiwoomTokenProvider tokens = mock(KiwoomTokenProvider.class);
    private final HttpClient http = mock(HttpClient.class);
    private final Clock clock = mock(Clock.class);
    private final List<WebSocket.Listener> listeners = new ArrayList<>();
    private final List<WebSocket> sockets = new ArrayList<>();
    private final List<String> sent = new ArrayList<>();
    private KiwoomStockStream stream;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(tokens.accessToken()).thenReturn("test-token");
        var builder = mock(WebSocket.Builder.class, RETURNS_SELF);
        when(http.newWebSocketBuilder()).thenReturn(builder);
        when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class))).thenAnswer(call -> {
            var listener = call.getArgument(1, WebSocket.Listener.class);
            var socket = mock(WebSocket.class);
            when(socket.sendText(anyString(), eq(true))).thenAnswer(send -> {
                sent.add(send.getArgument(0));
                return CompletableFuture.completedFuture(socket);
            });
            listeners.add(listener);
            sockets.add(socket);
            listener.onOpen(socket);
            return CompletableFuture.completedFuture(socket);
        });
        stream = new KiwoomStockStream(tokens, true, List.of("005930"), http, clock);
    }

    @AfterEach
    void tearDown() {
        stream.close();
    }

    @Test
    void waitsForLoginAndRegistrationThenParsesFragmentedTradeAndEchoesPing() {
        stream.maintainConnection();
        assertThat(stream.state()).isEqualTo(KiwoomStockStream.State.AUTHENTICATING);
        assertThat(sent).hasSize(1);
        assertThat(JsonMapper.builder().build().readTree(sent.getFirst()).path("trnm").asText())
                .isEqualTo("LOGIN");
        receive("{\"trnm\":\"LOGIN\",\"return_code\":0}");
        var registration = JsonMapper.builder().build().readTree(sent.get(1));
        assertThat(registration.path("data").get(0).path("item").get(0).asText()).isEqualTo("005930");
        assertThat(registration.path("data").get(0).path("type").get(0).asText()).isEqualTo("0B");
        assertThat(stream.state()).isEqualTo(KiwoomStockStream.State.SUBSCRIBING);
        receive("{\"trnm\":\"REG\",\"return_code\":0}");
        String trade = trade("005930", "0B", "-70000", "-100", "-0.14", "100000");
        listeners.getLast().onText(sockets.getLast(), trade.substring(0, 20), false);
        assertThat(stream.latest("005930")).isEmpty();
        listeners.getLast().onText(sockets.getLast(), trade.substring(20), true);
        var price = stream.latest("005930").orElseThrow();
        assertThat(price.currentPrice()).isEqualByComparingTo(new BigDecimal("70000"));
        assertThat(price.change()).isEqualByComparingTo("-100");
        assertThat(price.changeRate()).isEqualByComparingTo("-0.14");
        assertThat(price.tradeTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(price.receivedAt()).isEqualTo(NOW);
        receive("{\"trnm\":\"PING\"}");
        assertThat(sent.getLast()).isEqualTo("{\"trnm\":\"PING\"}");
        stream.maintainConnection();
        verify(tokens, times(1)).accessToken();
    }

    @Test
    void disconnectInvalidatesPricesAndOldConnectionCannotOverwriteNewSession() {
        subscribe();
        receive(trade("005930", "0B", "70000", "0", "0", "100000"));
        var old = listeners.getFirst();
        old.onClose(sockets.getFirst(), 1006, "not logged");
        assertThat(stream.latest("005930")).isEmpty();
        subscribe();
        receive(trade("005930", "0B", "71000", "1000", "1.4", "100001"));
        old.onText(sockets.getFirst(), trade("005930", "0B", "60000", "0", "0", "100000"), true);
        old.onError(sockets.getFirst(), new RuntimeException("not logged"));
        assertThat(stream.latest("005930").orElseThrow().currentPrice()).isEqualByComparingTo("71000");
        assertThat(listeners).hasSize(2);
        verify(tokens, times(2)).accessToken();
    }

    @Test
    void loginFailureInvalidatesOnlyRejectedTokenAndDoesNotSubscribe() {
        stream.maintainConnection();
        receive("{\"trnm\":\"LOGIN\",\"return_code\":3}");
        assertThat(stream.state()).isEqualTo(KiwoomStockStream.State.DISCONNECTED);
        assertThat(sent).hasSize(1);
        verify(tokens).invalidate("test-token");
        verify(sockets.getFirst()).abort();
    }

    @Test
    void registrationFailureDoesNotExposePrices() {
        stream.maintainConnection();
        receive("{\"trnm\":\"LOGIN\",\"return_code\":0}");
        receive("{\"trnm\":\"REG\",\"return_code\":1}");
        assertThat(stream.state()).isEqualTo(KiwoomStockStream.State.DISCONNECTED);
        assertThat(stream.latest("005930")).isEmpty();
    }

    @Test
    void filtersOtherSymbolsAndTypesAndRejectsInvalidPriceOrTime() {
        subscribe();
        receive(trade("000660", "0B", "70000", "0", "0", "100000"));
        receive(trade("005930", "0D", "70000", "0", "0", "100000"));
        assertThat(stream.latest("005930")).isEmpty();
        receive(trade("005930", "0B", "70000", "0", "0", "250000"));
        assertThat(stream.state()).isEqualTo(KiwoomStockStream.State.DISCONNECTED);
        subscribe();
        receive(trade("005930", "0B", "0", "0", "0", "100000"));
        assertThat(stream.latest("005930")).isEmpty();
        assertThat(stream.state()).isEqualTo(KiwoomStockStream.State.DISCONNECTED);
    }

    @Test
    void silenceDuringHandshakeTimesOutEvenIfPingKeepsArriving() {
        stream.maintainConnection();
        when(clock.instant()).thenReturn(NOW.plusSeconds(11));
        receive("{\"trnm\":\"PING\"}");
        stream.maintainConnection();
        assertThat(stream.state()).isEqualTo(KiwoomStockStream.State.DISCONNECTED);
    }

    @Test
    void heartbeatKeepsIdleStockConnectedButSilentConnectionIsInvalidated() {
        subscribe();
        receive(trade("005930", "0B", "70000", "0", "0", "100000"));
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        receive("{\"trnm\":\"PING\"}");
        when(clock.instant()).thenReturn(NOW.plusSeconds(100));
        stream.maintainConnection();
        assertThat(stream.latest("005930")).isPresent();
        when(clock.instant()).thenReturn(NOW.plusSeconds(151));
        assertThat(stream.latest("005930")).isEmpty();
        stream.maintainConnection();
        assertThat(stream.state()).isEqualTo(KiwoomStockStream.State.DISCONNECTED);
    }

    @Test
    void disabledOrStoppedStreamDoesNotConnect() {
        stream.close();
        stream = new KiwoomStockStream(tokens, false, List.of("005930"), http, clock);
        stream.maintainConnection();
        verifyNoInteractions(tokens);
        assertThat(stream.state()).isEqualTo(KiwoomStockStream.State.DISABLED);
        stream.close();
        stream.maintainConnection();
        assertThat(stream.state()).isEqualTo(KiwoomStockStream.State.STOPPED);
        verifyNoInteractions(tokens);
    }

    @Test
    void failedSendAndMalformedJsonDisconnectWithoutLeakingProviderText() {
        stream.maintainConnection();
        when(sockets.getLast().sendText(anyString(), eq(true)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("secret")));
        receive("{\"trnm\":\"LOGIN\",\"return_code\":0}");
        assertThat(stream.state()).isEqualTo(KiwoomStockStream.State.DISCONNECTED);
        subscribe();
        receive("invalid json");
        assertThat(stream.state()).isEqualTo(KiwoomStockStream.State.DISCONNECTED);
    }

    @Test
    void tokenPreparationFailureIsRetriedAtNextScheduledCheck() {
        when(tokens.accessToken()).thenThrow(new IllegalStateException("not logged"))
                .thenReturn("test-token");
        stream.maintainConnection();
        assertThat(stream.state()).isEqualTo(KiwoomStockStream.State.DISCONNECTED);
        assertThat(listeners).isEmpty();
        subscribe();
        assertThat(stream.state()).isEqualTo(KiwoomStockStream.State.SUBSCRIBED);
    }

    @Test
    void productionYamlContainsNestedStreamSettings() {
        var yaml = new org.springframework.beans.factory.config.YamlMapFactoryBean();
        yaml.setResources(new org.springframework.core.io.FileSystemResource("src/main/resources/application.yaml"));
        var root = yaml.getObject();
        var kiwoom = (java.util.Map<?, ?>) root.get("kiwoom");
        var settings = (java.util.Map<?, ?>) kiwoom.get("stream");
        assertThat(settings.get("enabled")).isEqualTo("$" + "{KIWOOM_STREAM_ENABLED:false}");
    }

    private void subscribe() {
        stream.maintainConnection();
        receive("{\"trnm\":\"LOGIN\",\"return_code\":0}");
        receive("{\"trnm\":\"REG\",\"return_code\":0}");
    }

    private void receive(String text) {
        listeners.getLast().onText(sockets.getLast(), text, true);
    }

    private String trade(String code, String type, String price, String change, String rate, String time) {
        return """
                {"trnm":"REAL","data":[{"type":"%s","item":"%s",
                "values":{"10":"%s","11":"%s","12":"%s","20":"%s"}}]}
                """.formatted(type, code, price, change, rate, time);
    }
}


