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

    @Test
    void savesBeforeSubscriptionAndKeepsOrderWhenRedisIsDown() {
        var saved = mock(Order.class);
        when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenReturn(Set.of("005930"));
        doThrow(new IllegalStateException("Redis down")).when(stream).updateSymbols(anySet());
        assertThat(service.create("005930", () -> saved)).isSameAs(saved);
        verify(stream, never()).whenSubscribed(anyString());
        verify(stream, never()).retainRequest(anyString(), anyString());
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
