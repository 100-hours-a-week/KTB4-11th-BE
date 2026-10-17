package com.stock_spoon.river_be.market;

import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import com.stock_spoon.river_be.market.kiwoom.RedisCollectorHeartbeat;
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
    private final RedisQuoteStore quotes = mock(RedisQuoteStore.class);
    private final RedisCollectorHeartbeat owner = mock(RedisCollectorHeartbeat.class);
    private final RedisMessageListenerContainer channels = mock(RedisMessageListenerContainer.class);

    private AiQuoteAdapter adapter() {
        when(redis.opsForValue()).thenReturn(values);
        when(owner.ownsLease()).thenReturn(true);
        when(owner.ownerId()).thenReturn("be-relay");
        return new AiQuoteAdapter(redis, quotes, owner, channels, Clock.fixed(NOW, ZoneOffset.UTC));
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
        verifyNoInteractions(quotes);
        verify(redis, never()).convertAndSend(anyString(), anyString());
    }

    @Test void livePriceUsesReceiptTimeAndDeduplicatesWithinSession() {
        var adapter = adapter();
        adapter.updateSymbols(Set.of("005930"));
        adapter.onChannelSubscribed(bytes("price:005930"), 1);
        adapter.onChannelSubscribed(bytes("orderbook:005930"), 2);
        clearInvocations(quotes);
        adapter.onMessage(message("price:005930", PRICE), null);
        adapter.onMessage(message("price:005930", PRICE), null);
        verify(quotes, times(1)).savePrice(argThat(price -> NOW.equals(price.receivedAt())
                && NOW.minusSeconds(1).equals(price.exchangeTimestamp())), any());
    }

    @Test void rejectsWrongCodeOldDayFutureMalformedAndLostOwnership() {
        var adapter = adapter();
        adapter.updateSymbols(Set.of("005930"));
        adapter.onChannelSubscribed(bytes("price:005930"), 1);
        clearInvocations(quotes);
        for (String payload : new String[]{PRICE.replace("005930", "000660"),
                PRICE.replace("2026-10-16", "2026-10-15"),
                PRICE.replace("10:00:01", "10:01:01"), PRICE.replace("70000", "0"), "{}"}) {
            adapter.onMessage(message("price:005930", payload), null);
        }
        when(owner.ownsLease()).thenReturn(false);
        adapter.onMessage(message("price:005930", PRICE), null);
        verify(quotes, never()).savePrice(any(), any());
    }

    @Test void priceAndBookAckShareOneSessionButReconnectRotatesIt() {
        var adapter = adapter();
        adapter.updateSymbols(Set.of("005930"));
        adapter.onChannelSubscribed(bytes("price:005930"), 1);
        adapter.onChannelSubscribed(bytes("orderbook:005930"), 2);
        var sources = org.mockito.ArgumentCaptor.forClass(QuoteStore.Source.class);
        verify(quotes, times(1)).register(eq("005930"), sources.capture(), eq(NOW));
        var first = sources.getValue();
        adapter.onChannelSubscribed(bytes("orderbook:005930"), 1);
        adapter.onChannelSubscribed(bytes("price:005930"), 2);
        verify(quotes, times(2)).register(eq("005930"), sources.capture(), eq(NOW));
        assertThat(sources.getValue()).isNotEqualTo(first);
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
        verify(quotes, never()).savePrice(any(), any());
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static Message message(String channel, String body) {
        return new org.springframework.data.redis.connection.DefaultMessage(bytes(channel), bytes(body));
    }
}
