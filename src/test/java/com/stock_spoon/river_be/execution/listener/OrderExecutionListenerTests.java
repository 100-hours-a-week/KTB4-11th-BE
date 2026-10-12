package com.stock_spoon.river_be.execution.listener;

import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.execution.repository.ExecutionRepository;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.execution.service.OrderBuyPriceService;
import com.stock_spoon.river_be.execution.service.OrderExecutionService;
import com.stock_spoon.river_be.order.service.OrderSubscriptionService;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class OrderExecutionListenerTests {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderExecutionService execution = mock(OrderExecutionService.class);
    private final OrderSubscriptionService subscriptions = mock(OrderSubscriptionService.class);
    private final KiwoomStockStream stream = mock(KiwoomStockStream.class);
    private final KiwoomMarketClient market = mock(KiwoomMarketClient.class);
    private final OrderBuyPriceService buyPrices = mock(OrderBuyPriceService.class);
    private final OrderExecutionListener listener = new OrderExecutionListener(orders,
            mock(ExecutionRepository.class), execution, subscriptions, stream, market, buyPrices);
    private final Instant now = Instant.parse("2026-10-01T01:00:00Z");

    @org.junit.jupiter.api.BeforeEach
    void setupQuoteAdapter() {
        doCallRealMethod().when(stream).latestQuote(anyString());
    }

    private Order order(long id) {
        var order = mock(Order.class);
        when(order.getId()).thenReturn(id);
        when(order.getAccountId()).thenReturn(1L);
        when(order.getStockCode()).thenReturn("005930");
        when(order.getCreatedAt()).thenReturn(now);
        return order;
    }

    private KiwoomStockStream.StockPrice price(long value) {
        return new KiwoomStockStream.StockPrice("005930", BigDecimal.valueOf(value),
                BigDecimal.ZERO, BigDecimal.ZERO, LocalTime.of(10, 0), now);
    }

    @Test
    void nonMatchingBuyAndSellSkipExecutionAndValuation() {
        var buy = order(1);
        when(buy.getSide()).thenReturn(Order.Side.BUY);
        when(buy.getLimitPrice()).thenReturn(68000L);
        var sell = order(2);
        when(sell.getSide()).thenReturn(Order.Side.SELL);
        when(sell.getLimitPrice()).thenReturn(70000L);
        when(orders.findPendingForPrice("005930", Order.Status.PENDING, Order.Type.LIMIT))
                .thenReturn(List.of(buy, sell));
        listener.onPrice(price(69000));
        verifyNoInteractions(execution, buyPrices);
        when(stream.latest("005930")).thenReturn(Optional.of(price(69000)));
        listener.orderCreated(buy);
        verifyNoInteractions(execution, buyPrices);
        verify(subscriptions).refresh();
    }

    @Test
    void matchingAndEqualBuyAndSellStillEnterLockedExecution() {
        var buy = order(1);
        when(buy.getSide()).thenReturn(Order.Side.BUY);
        when(buy.getLimitPrice()).thenReturn(69000L);
        var sell = order(2);
        when(sell.getSide()).thenReturn(Order.Side.SELL);
        when(sell.getLimitPrice()).thenReturn(69000L);
        when(orders.findPendingForPrice("005930", Order.Status.PENDING, Order.Type.LIMIT))
                .thenReturn(List.of(buy, sell));
        listener.onPrice(price(69000));
        listener.onPrice(price(68000));
        listener.onPrice(price(70000));
        verify(execution).executeLimit(eq(1L), eq(1L), eq(69000L), anyMap(), any(Runnable.class));
        verify(execution).executeLimit(eq(1L), eq(2L), eq(69000L), anyMap(), any(Runnable.class));
        verify(execution).executeLimit(eq(1L), eq(1L), eq(68000L), anyMap(), any(Runnable.class));
        verify(execution).executeLimit(eq(1L), eq(2L), eq(70000L), anyMap(), any(Runnable.class));
        verify(execution, never()).executeLimit(eq(1L), eq(2L), eq(68000L), anyMap(), any(Runnable.class));
        verify(execution, never()).executeLimit(eq(1L), eq(1L), eq(70000L), anyMap(), any(Runnable.class));
    }

    @Test
    void initialCachedPriceKeepsSourceUntilExecution() {
        var source = new com.stock_spoon.river_be.market.QuoteStore.Source("owner", "old-session");
        var data = price(69000);
        doReturn(Optional.of(
                new com.stock_spoon.river_be.market.RedisQuoteStore.QuoteEvent("005930", "price", data.receivedAt(), source, data, null)))
                .when(stream).latestQuote("005930");
        doThrow(new IllegalStateException("previous session")).when(stream).assertSource("005930", source);
        listener.orderCreated(order(1));
        verify(stream).assertSource("005930", source);
        verifyNoInteractions(execution, market);
    }

    @Test
    void oldSessionEventNeverExecutesEvenIfAnotherSessionIsConnected() {
        var source = new com.stock_spoon.river_be.market.QuoteStore.Source("owner", "old-session");
        var data = price(69000);
        var event = new com.stock_spoon.river_be.market.RedisQuoteStore.QuoteEvent("005930", "price",
                data.receivedAt(), source, data, null);
        doThrow(new IllegalStateException("previous session")).when(stream).assertSource("005930", source);
        listener.onQuote(event);
        verifyNoInteractions(execution, orders);
    }

    @Test
    void initialPriceUsesCacheThenRestAndFailureWaitsForWebsocket() {
        when(stream.latest("005930")).thenReturn(Optional.of(price(69000)));
        listener.orderCreated(order(1));
        verify(execution).executeLimit(eq(1L), eq(1L), eq(69000L), anyMap(), any(Runnable.class));
        verifyNoInteractions(market);

        when(stream.latest("005930")).thenReturn(Optional.empty());
        when(market.currentPrice("005930")).thenReturn(68000L);
        listener.orderCreated(order(2));
        verify(execution).executeLimit(eq(1L), eq(2L), eq(68000L), anyMap(), any(Runnable.class));

        when(market.currentPrice("005930")).thenThrow(new IllegalStateException());
        listener.orderCreated(order(3));
        verify(execution, never()).executeLimit(eq(1L), eq(3L), anyLong(), anyMap(), any(Runnable.class));
        verify(subscriptions, times(3)).refresh();
    }

    @Test
    void failedOrderDoesNotStopOtherOrdersAndEventsBeforeCreationAreSkipped() {
        var first = order(1);
        var second = order(2);
        var future = order(3);
        when(future.getCreatedAt()).thenReturn(now.plusSeconds(1));
        when(orders.findPendingForPrice("005930", Order.Status.PENDING, Order.Type.LIMIT))
                .thenReturn(List.of(first, second, future));
        when(execution.executeLimit(eq(1L), eq(1L), eq(69000L), anyMap(), any(Runnable.class))).thenThrow(new IllegalStateException());
        when(execution.executeLimit(eq(1L), eq(2L), eq(69000L), anyMap(), any(Runnable.class))).thenReturn(true);
        listener.onPrice(price(69000));
        verify(execution).executeLimit(eq(1L), eq(2L), eq(69000L), anyMap(), any(Runnable.class));
        verify(execution, never()).executeLimit(eq(1L), eq(3L), anyLong(), anyMap(), any(Runnable.class));
        verify(subscriptions).refresh();
    }

    @Test
    void websocketPriceArrivingDuringRestLookupTakesPrecedence() {
        when(stream.latest("005930")).thenReturn(Optional.empty(), Optional.of(price(69000)));
        when(market.currentPrice("005930")).thenReturn(68000L);
        listener.orderCreated(order(1));
        verify(execution).executeLimit(eq(1L), eq(1L), eq(69000L), anyMap(), any(Runnable.class));
    }
}
