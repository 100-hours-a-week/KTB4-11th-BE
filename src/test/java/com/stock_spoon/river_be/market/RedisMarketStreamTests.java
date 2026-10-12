package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream;
import com.stock_spoon.river_be.market.kiwoom.RedisCollectorHeartbeat;
import com.stock_spoon.river_be.order.Order;
import com.stock_spoon.river_be.order.OrderRepository;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import static org.mockito.Mockito.*;

class RedisMarketStreamTests {
    @Test
    void commitBetweenDemandAndPendingReadsDoesNotDropSubscription() {
        var quotes = mock(RedisQuoteStore.class);
        var demands = mock(RedisSubscriptionDemand.class);
        var collector = mock(KiwoomStockStream.class);
        var heartbeat = mock(RedisCollectorHeartbeat.class);
        var orders = mock(OrderRepository.class);
        var committed = new AtomicBoolean();
        when(heartbeat.ownsLease()).thenReturn(true);
        when(demands.symbols()).thenAnswer(ignored -> {
            committed.set(true); // 주문 커밋 후 임시 표시가 해제된 순간
            return Set.of();
        });
        when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenAnswer(ignored ->
                committed.get() ? Set.of("005930") : Set.of());
        var stream = new RedisMarketStream(quotes, demands, collector, heartbeat,
                mock(RedisMessageListenerContainer.class), event -> {}, orders);
        try {
            stream.reconcile();
            verify(collector).updateSymbols(Set.of("005930"));
        } finally { stream.close(); }
    }
}
