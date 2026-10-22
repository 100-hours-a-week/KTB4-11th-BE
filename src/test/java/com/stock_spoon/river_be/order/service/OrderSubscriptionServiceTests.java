package com.stock_spoon.river_be.order.service;

import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.market.MarketStream;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderSubscriptionServiceTests {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final MarketStream stream = mock(MarketStream.class);
    private final OrderSubscriptionService service = new OrderSubscriptionService(orders, stream);

    private com.stock_spoon.river_be.market.QuoteEvent quote() {
        var now = java.time.Instant.now();
        var book = new com.stock_spoon.river_be.market.OrderBook("005930", java.util.List.of(),
                java.util.List.of(), java.time.LocalTime.of(10, 0), now);
        return new com.stock_spoon.river_be.market.QuoteEvent("005930", "orderbook", now,
                new com.stock_spoon.river_be.market.QuoteSource("subscriber", "session"), null, book);
    }

    @Test
    void marketQuoteSubscribesNewSymbolAndPreservesPendingDemandAfterSuccess() {
        when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenReturn(Set.of("000660"));
        var quote = quote();
        when(stream.latestBookQuote("005930")).thenReturn(java.util.Optional.of(quote));
        var saved = mock(Order.class);
        assertThat(service.withMarketQuote("005930", actual -> {
            assertThat(actual).isSameAs(quote);
            service.refresh();
            return saved;
        })).isSameAs(saved);
        var sequence = inOrder(stream);
        sequence.verify(stream).updateSymbols(Set.of("005930", "000660"));
        sequence.verify(stream).latestBookQuote("005930");
        sequence.verify(stream).assertSource("005930", quote.source());
        sequence.verify(stream).updateSymbols(Set.of("005930", "000660"));
        sequence.verify(stream).updateSymbols(Set.of("000660"));
    }

    @Test
    void marketQuoteTimeoutReleasesDemandWithoutExecuting() {
        when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenReturn(Set.of());
        var immediate = new OrderSubscriptionService(orders, stream, java.time.Duration.ZERO);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> immediate.withMarketQuote("005930", quote -> {
            calls.incrementAndGet();
            return null;
        })).isInstanceOfSatisfying(OrderException.class,
                error -> assertThat(error.code()).isEqualTo("MARKET_STREAM_UNAVAILABLE"));
        assertThat(calls.get()).isZero();
        verify(stream).updateSymbols(Set.of());
    }

    @Test
    void marketCallbackFailureAndSourceLossReleaseDemand() {
        when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenReturn(Set.of());
        var quote = quote();
        when(stream.latestBookQuote("005930")).thenReturn(java.util.Optional.of(quote));
        assertThatThrownBy(() -> service.withMarketQuote("005930", actual -> {
            throw new OrderException("잔액 부족");
        })).isInstanceOf(OrderException.class).hasMessage("잔액 부족");
        doThrow(new IllegalStateException("old session")).when(stream).assertSource("005930", quote.source());
        assertThatThrownBy(() -> service.withMarketQuote("005930", actual -> {
            fail("source mismatch must not execute");
            return null;
        })).isInstanceOfSatisfying(OrderException.class,
                error -> assertThat(error.code()).isEqualTo("MARKET_STREAM_UNAVAILABLE"));
        verify(stream, times(2)).updateSymbols(Set.of());
    }

    @Test
    void subscriptionFailureIsNotAllowedToExecuteAndCleanupFailureDoesNotHideSuccess() {
        when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenReturn(Set.of());
        doThrow(new IllegalStateException("Redis down")).when(stream).updateSymbols(Set.of("005930"));
        assertThatThrownBy(() -> service.withMarketQuote("005930", actual -> {
            fail("subscription failure must not execute");
            return null;
        })).isInstanceOfSatisfying(OrderException.class,
                error -> assertThat(error.code()).isEqualTo("MARKET_STREAM_UNAVAILABLE"));
        reset(stream);
        when(stream.latestBookQuote("005930")).thenReturn(java.util.Optional.of(quote()));
        doThrow(new IllegalStateException("cleanup failed")).when(stream).updateSymbols(Set.of());
        var saved = mock(Order.class);
        assertThat(service.withMarketQuote("005930", actual -> saved)).isSameAs(saved);
    }

    @Test
    void simultaneousMarketRequestsKeepSubscriptionUntilLastRequestFinishes() throws Exception {
        when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenReturn(Set.of());
        when(stream.latestBookQuote("005930")).thenReturn(java.util.Optional.of(quote()));
        var symbols = new java.util.concurrent.atomic.AtomicReference<Set<String>>(Set.of());
        doAnswer(call -> { symbols.set(Set.copyOf(call.getArgument(0))); return null; }).when(stream).updateSymbols(anySet());
        var entered = new java.util.concurrent.CountDownLatch(2);
        var releaseFirst = new java.util.concurrent.CountDownLatch(1);
        var releaseSecond = new java.util.concurrent.CountDownLatch(1);
        var saved = mock(Order.class);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.function.Function<java.util.concurrent.CountDownLatch, Order> request = release ->
                    service.withMarketQuote("005930", actual -> {
                        entered.countDown();
                        try {
                            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                        return saved;
                    });
            var first = pool.submit(() -> request.apply(releaseFirst));
            var second = pool.submit(() -> request.apply(releaseSecond));
            try {
                assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                service.refresh();
                releaseFirst.countDown();
                assertThat(first.get(2, java.util.concurrent.TimeUnit.SECONDS)).isSameAs(saved);
                assertThat(symbols.get()).containsExactly("005930");
                releaseSecond.countDown();
                assertThat(second.get(2, java.util.concurrent.TimeUnit.SECONDS)).isSameAs(saved);
                assertThat(symbols.get()).isEmpty();
            } finally { releaseFirst.countDown(); releaseSecond.countDown(); }
        }
    }

    @Test
    void waitsForQuoteArrivalAndRejectsMissingSource() {
        when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenReturn(Set.of());
        var quote = quote();
        when(stream.latestBookQuote("005930")).thenReturn(java.util.Optional.empty(), java.util.Optional.of(quote));
        var saved = mock(Order.class);
        assertThat(service.withMarketQuote("005930", actual -> saved)).isSameAs(saved);
        var missingSource = new com.stock_spoon.river_be.market.QuoteEvent("005930", "orderbook",
                quote.receivedAt(), null, null, quote.orderBook());
        when(stream.latestBookQuote("005930")).thenReturn(java.util.Optional.of(missingSource));
        assertThatThrownBy(() -> service.withMarketQuote("005930", actual -> {
            fail("source metadata is required");
            return null;
        })).isInstanceOfSatisfying(OrderException.class,
                error -> assertThat(error.code()).isEqualTo("MARKET_STREAM_UNAVAILABLE"));
        verify(stream, times(2)).updateSymbols(Set.of());
    }

    @Test
    void interruptedQuoteWaitRestoresInterruptAndReleasesDemand() {
        when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenReturn(Set.of());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> service.withMarketQuote("005930", actual -> {
                fail("interrupted request must not execute");
                return null;
            })).isInstanceOfSatisfying(OrderException.class,
                    error -> assertThat(error.code()).isEqualTo("MARKET_STREAM_UNAVAILABLE"));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        verify(stream).updateSymbols(Set.of());
    }

    @Test
    void savesBeforeSubscriptionAndKeepsOrderWhenRedisIsDown() {
        var saved = mock(Order.class);
        when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenReturn(Set.of("005930"));
        doThrow(new IllegalStateException("Redis down")).when(stream).updateSymbols(anySet());
        assertThat(service.create("005930", () -> saved)).isSameAs(saved);
        reset(stream);
        service.refresh();
        verify(stream).updateSymbols(Set.of("005930"));
    }

    @Test
    void databaseFailureRemainsFailureAndDoesNotSubscribe() {
        assertThatThrownBy(() -> service.create("005930", () -> {
            throw new OrderException("잔액 부족");
        })).isInstanceOf(OrderException.class).hasMessage("잔액 부족");
        verifyNoInteractions(stream, orders);
    }

    @Test
    void startupRestoresPendingAndCancelRemovesOnlyLastDemand() {
        when(orders.findStockCodesByStatus(Order.Status.PENDING))
                .thenReturn(Set.of("005930", "000660"), Set.of("000660"));
        service.restorePendingSubscriptions();
        service.refresh();
        var sequence = inOrder(stream);
        sequence.verify(stream).updateSymbols(Set.of("005930", "000660"));
        sequence.verify(stream).updateSymbols(Set.of("000660"));
    }
}
