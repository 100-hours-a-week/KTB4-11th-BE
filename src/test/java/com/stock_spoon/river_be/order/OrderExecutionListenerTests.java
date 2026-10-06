package com.stock_spoon.river_be.order;

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
    private final OrderExecutionListener listener = new OrderExecutionListener(orders,
            mock(ExecutionRepository.class), execution, subscriptions, stream, market, mock(OrderBuyPriceService.class));
    private final Instant now = Instant.parse("2026-10-01T01:00:00Z");

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
    void initialPriceUsesCacheThenRestAndFailureWaitsForWebsocket() {
        when(stream.latest("005930")).thenReturn(Optional.of(price(69000)));
        listener.orderCreated(order(1));
        verify(execution).executeLimit(1, 1, 69000);
        verifyNoInteractions(market);

        when(stream.latest("005930")).thenReturn(Optional.empty());
        when(market.currentPrice("005930")).thenReturn(68000L);
        listener.orderCreated(order(2));
        verify(execution).executeLimit(1, 2, 68000);

        when(market.currentPrice("005930")).thenThrow(new IllegalStateException());
        listener.orderCreated(order(3));
        verify(execution, never()).executeLimit(eq(1L), eq(3L), anyLong());
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
        when(execution.executeLimit(1, 1, 69000)).thenThrow(new IllegalStateException());
        when(execution.executeLimit(1, 2, 69000)).thenReturn(true);
        listener.onPrice(price(69000));
        verify(execution).executeLimit(1, 2, 69000);
        verify(execution, never()).executeLimit(eq(1L), eq(3L), anyLong());
        verify(subscriptions).refresh();
    }

    @Test
    void websocketPriceArrivingDuringRestLookupTakesPrecedence() {
        when(stream.latest("005930")).thenReturn(Optional.empty(), Optional.of(price(69000)));
        when(market.currentPrice("005930")).thenReturn(68000L);
        listener.orderCreated(order(1));
        verify(execution).executeLimit(1, 1, 69000);
    }
}