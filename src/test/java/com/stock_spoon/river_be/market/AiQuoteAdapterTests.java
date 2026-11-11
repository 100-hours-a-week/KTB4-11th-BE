package com.stock_spoon.river_be.market;

import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.context.ApplicationEventPublisher;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiQuoteAdapterTests {
    private static final Instant NOW = Instant.parse("2026-10-16T01:00:02Z");
    private static final String PRICE = """
            {"code":"005930","price":70000,"change":-100,"rate":-0.14,"volume":1234,
             "timestamp":"2026-10-16T10:00:01+09:00"}
            """;
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RedisMessageListenerContainer channels = mock(RedisMessageListenerContainer.class);

    private AiQuoteAdapter adapter() {
        when(redis.opsForValue()).thenReturn(values);
        when(channels.isListening()).thenReturn(true);
        return new AiQuoteAdapter(redis, channels, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void liveMessagesIgnoreMissingAndInvalidSourceTimestamps() {
        var adapter = adapter();
        adapter.updateSymbols(Set.of("005930"));
        adapter.onChannelSubscribed(bytes("price:005930"), 1);
        adapter.onChannelSubscribed(bytes("orderbook:005930"), 2);
        String price = PRICE.replace("\"timestamp\":\"2026-10-16T10:00:01+09:00\"", "\"unused\":true");
        String book = """
                {"code":"005930","asks":[{"price":70100,"qty":10}],
                 "bids":[{"price":70000,"qty":20}],"timestamp":"invalid"}
                """;
        adapter.onMessage(message("price:005930", price), null);
        adapter.onMessage(message("orderbook:005930", book), null);
        assertThat(adapter.latestQuote("005930").orElseThrow().receivedAt()).isEqualTo(NOW);
        assertThat(adapter.latestBookQuote("005930").orElseThrow().receivedAt()).isEqualTo(NOW);
    }

    @Test void sameDayVolumeDecreaseIsRejectedButNextDayIsAccepted() {
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(1), NOW.plusSeconds(86400));
        when(channels.isListening()).thenReturn(true);
        var adapter = new AiQuoteAdapter(redis, channels, events, clock);
        adapter.updateSymbols(Set.of("005930"));
        adapter.onChannelSubscribed(bytes("price:005930"), 1);
        adapter.onMessage(message("price:005930", PRICE), null);
        adapter.onMessage(message("price:005930", PRICE.replace("1234", "1233")), null);
        assertThat(adapter.latestQuote("005930").orElseThrow().price().volume()).isEqualTo(1234);
        adapter.onMessage(message("price:005930", PRICE.replace("1234", "1233")), null);
        assertThat(adapter.latestQuote("005930").orElseThrow().price().volume()).isEqualTo(1233);
    }

    @Test void snapshotMapsSignedFieldsWithoutInventingReceiptOrPublishing() {
        var adapter = adapter();
        when(values.get("price:005930")).thenReturn(PRICE);
        var price = adapter.latestPrice("005930").orElseThrow();
        assertThat(price.currentPrice()).isEqualByComparingTo("70000");
        assertThat(price.change()).isEqualByComparingTo("-100");
        assertThat(price.changeRate()).isEqualByComparingTo("-0.14");
        assertThat(price.tradeTime()).isEqualTo(LocalTime.of(10, 0, 1));
        assertThat(price.exchangeTimestamp()).isEqualTo(NOW.minusSeconds(1));
        assertThat(price.receivedAt()).isNull();
        verifyNoInteractions(events);
        verify(redis, never()).convertAndSend(anyString(), anyString());
    }

    @Test void livePriceRefreshesReceiptOnRepeatedMessages() {
        var adapter = adapter();
        adapter.updateSymbols(Set.of("005930"));
        adapter.onChannelSubscribed(bytes("price:005930"), 1);
        adapter.onChannelSubscribed(bytes("orderbook:005930"), 2);
        clearInvocations(events);
        adapter.onMessage(message("price:005930", PRICE), null);
        adapter.onMessage(message("price:005930", PRICE), null);
        verify(events, times(2)).publishEvent((Object) argThat(event -> event instanceof QuoteEvent quote
                && NOW.equals(quote.price().receivedAt()) && quote.price().exchangeTimestamp() == null));
        assertThat(adapter.latestQuote("005930")).isPresent();
        verifyNoInteractions(redis);
    }

    @Test void rejectsWrongCodeMalformedAndDisconnectedChannel() {
        var adapter = adapter();
        adapter.updateSymbols(Set.of("005930"));
        adapter.onChannelSubscribed(bytes("price:005930"), 1);
        clearInvocations(events);
        for (String payload : new String[]{PRICE.replace("005930", "000660"),
                PRICE.replace("70000", "0"), "{}"}) {
            adapter.onMessage(message("price:005930", payload), null);
        }
        when(channels.isListening()).thenReturn(false);
        adapter.onMessage(message("price:005930", PRICE), null);
        verifyNoInteractions(events);
    }

    @Test void priceAndBookAckShareOneSessionButReconnectRotatesIt() {
        var adapter = adapter();
        adapter.updateSymbols(Set.of("005930"));
        adapter.onChannelSubscribed(bytes("price:005930"), 1);
        adapter.onChannelSubscribed(bytes("orderbook:005930"), 2);
        adapter.onMessage(message("price:005930", PRICE), null);
        var first = adapter.latestQuote("005930").orElseThrow().source();
        clearInvocations(events);
        adapter.onChannelSubscribed(bytes("orderbook:005930"), 1);
        adapter.onChannelSubscribed(bytes("price:005930"), 2);
        assertThat(adapter.latestQuote("005930")).isEmpty();
        assertThatThrownBy(() -> adapter.assertSource("005930", first)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(events);
        adapter.onMessage(message("price:005930", PRICE), null);
        var second = adapter.latestQuote("005930").orElseThrow().source();
        assertThat(second).isNotEqualTo(first);
        adapter.assertSource("005930", second);
    }

    @Test void bookMapsQtyAndRejectsFractionalAndNegativeQuantities() {
        var adapter = adapter();
        String book = """
                {"code":"005930","asks":[{"price":70100,"qty":10}],
                 "bids":[{"price":70000,"qty":20}],"timestamp":"2026-10-16T10:00:01+09:00"}
                """;
        when(values.get("orderbook:005930")).thenReturn(book);
        assertThat(adapter.latestOrderBook("005930").orElseThrow().asks().getFirst().quantity()).isEqualTo(10);
        for (String invalid : new String[]{book.replace("\"qty\":10", "\"qty\":-1"),
                book.replace("\"qty\":10", "\"qty\":1.5"), book.replace("70100", "0")}) {
            when(values.get("orderbook:005930")).thenReturn(invalid);
            assertThat(adapter.latestOrderBook("005930")).isEmpty();
        }
    }

    @Test void ackAndRemovalNeverReadOrDeleteAiSnapshots() {
        var adapter = adapter();
        adapter.updateSymbols(Set.of("005930"));
        adapter.onChannelSubscribed(bytes("price:005930"), 1);
        adapter.updateSymbols(Set.of());
        adapter.onMessage(message("price:005930", PRICE), null);
        verifyNoInteractions(values);
        verify(redis, never()).delete(anyString());
        verifyNoInteractions(events);
    }

    @Test void unsubscribedGenerationCannotExecuteAndRegistrationFailureCanRetry() {
        var adapter = adapter();
        doThrow(new IllegalStateException("Redis unavailable")).doNothing().when(channels)
                .addMessageListener(eq(adapter), org.mockito.ArgumentMatchers.<org.springframework.data.redis.listener.Topic>anyCollection());
        assertThatThrownBy(() -> adapter.updateSymbols(Set.of("005930"))).isInstanceOf(IllegalStateException.class);
        adapter.updateSymbols(Set.of("005930"));
        adapter.onChannelSubscribed(bytes("price:005930"), 1);
        adapter.onMessage(message("price:005930", PRICE), null);
        var source = adapter.latestQuote("005930").orElseThrow().source();
        clearInvocations(events);
        adapter.onChannelUnsubscribed(bytes("price:005930"), 0);
        assertThat(adapter.latestQuote("005930")).isEmpty();
        assertThatThrownBy(() -> adapter.assertSource("005930", source)).isInstanceOf(IllegalStateException.class);
        adapter.onMessage(message("price:005930", PRICE), null);
        verifyNoInteractions(events);
    }

    @Test void failedLocalListenerDoesNotStopFollowingTicks() {
        var adapter = adapter();
        adapter.updateSymbols(Set.of("005930"));
        adapter.onChannelSubscribed(bytes("price:005930"), 1);
        doThrow(new IllegalStateException("DB failure")).doNothing().when(events).publishEvent(any(Object.class));
        assertThatCode(() -> adapter.onMessage(message("price:005930", PRICE), null)).doesNotThrowAnyException();
        adapter.onMessage(message("price:005930", PRICE.replace("1234", "1235")), null);
        verify(events, times(2)).publishEvent(any(Object.class));
    }

    @Test void directGetIgnoresTimestampButValidatesDataAndDoesNotInventReceipt() {
        var adapter = adapter();
        String book = """
                {"code":"005930","asks":[{"price":70100,"qty":10}],"bids":[{"price":70000,"qty":20}],"timestamp":"invalid"}
                """;
        for (String payload : new String[]{book, book.replace(",\"timestamp\":\"invalid\"", "")}) {
            when(values.get("orderbook:005930")).thenReturn(payload);
            var actual = adapter.readOrderBookSnapshot("005930").orElseThrow();
            assertThat(actual.directSnapshot()).isTrue();
            assertThat(actual.receivedAt()).isNull();
            assertThat(actual.exchangeTimestamp()).isNull();
            assertThat(actual.asks().getFirst().price()).isEqualByComparingTo("70100");
        }
        when(values.get("orderbook:005930")).thenReturn(book.replace("70100", "-1"));
        assertThatThrownBy(() -> adapter.readOrderBookSnapshot("005930")).isInstanceOf(IllegalStateException.class);
        when(values.get("orderbook:005930")).thenReturn((String) null);
        assertThat(adapter.readOrderBookSnapshot("005930")).isEmpty();
        when(values.get("orderbook:005930")).thenThrow(new IllegalStateException("connection failed"));
        assertThatThrownBy(() -> adapter.readOrderBookSnapshot("005930")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(events);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static Message message(String channel, String body) {
        return new org.springframework.data.redis.connection.DefaultMessage(bytes(channel), bytes(body));
    }
}
