package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.market.kiwoom.RedisCollectorHeartbeat;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import static org.mockito.Mockito.*;

class RedisMarketStreamTests {
    @Test
    void subscriptionAckDoesNotReplayStoredPriceAsNewExecutionEvent() {
        var quotes = mock(RedisQuoteStore.class);
        var events = mock(org.springframework.context.ApplicationEventPublisher.class);
        var stream = new RedisMarketStream(quotes,
                mock(AiQuoteAdapter.class), mock(RedisCollectorHeartbeat.class),
                mock(RedisMessageListenerContainer.class), events, mock(OrderRepository.class));
        try {
            stream.onChannelSubscribed("quotes:005930".getBytes(java.nio.charset.StandardCharsets.UTF_8), 1);
            verifyNoInteractions(quotes, events);
        } finally { stream.close(); }
    }

    @Test
    void collectorSubscribesToCommittedPendingOrders() {
        var quotes = mock(RedisQuoteStore.class);
        var collector = mock(AiQuoteAdapter.class);
        var heartbeat = mock(RedisCollectorHeartbeat.class);
        var orders = mock(OrderRepository.class);
        when(heartbeat.ownsLease()).thenReturn(true);
        when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenReturn(Set.of("005930"));
        var stream = new RedisMarketStream(quotes, collector, heartbeat,
                mock(RedisMessageListenerContainer.class), event -> {}, orders);
        try {
            stream.reconcile();
            verify(collector).updateSymbols(Set.of("005930"));
        } finally { stream.close(); }
    }

    @Test
    void ordinaryBeDoesNotCollectOrQueryPendingOrders() {
        var collector = mock(AiQuoteAdapter.class);
        var orders = mock(OrderRepository.class);
        var stream = new RedisMarketStream(mock(RedisQuoteStore.class), collector,
                mock(RedisCollectorHeartbeat.class), mock(RedisMessageListenerContainer.class),
                event -> {}, orders);
        try {
            stream.reconcile();
            verify(collector).updateSymbols(Set.of());
            verifyNoInteractions(orders);
        } finally { stream.close(); }
    }
}
