package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.execution.service.MarketOrderPricingService;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.order.service.OrderSubscriptionService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "MARKET_FALLBACK_LIVE_TEST", matches = "true")
class MarketBookFallbackLiveTests {
    @Test void expiredPubSubBookForcesRealGetAndUsesStoredPriceWithoutTimestamp() {
        int port = Integer.parseInt(System.getenv().getOrDefault("MARKET_FALLBACK_PORT", "6392"));
        var connection = new LettuceConnectionFactory("127.0.0.1", port);
        connection.afterPropertiesSet(); connection.start();
        var redis = new StringRedisTemplate(connection);
        var channels = mock(RedisMessageListenerContainer.class);
        when(channels.isListening()).thenReturn(true);
        var adapter = new AiQuoteAdapter(redis, channels, event -> {}, Clock.fixed(Instant.now().minusSeconds(6), ZoneOffset.UTC));
        String code = "TST286", key = "orderbook:" + code;
        String live = "{\"code\":\"TST286\",\"asks\":[{\"price\":70000,\"qty\":2}],\"bids\":[{\"price\":69900,\"qty\":2}]}";
        boolean owned = false;
        try {
            owned = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, live.replace("70000", "70100"), Duration.ofMinutes(2)));
            assertThat(owned).as("protect pre-existing key").isTrue();
            adapter.updateSymbols(Set.of(code));
            adapter.onChannelSubscribed(key.getBytes(StandardCharsets.UTF_8), 1);
            adapter.onMessage(new DefaultMessage(key.getBytes(StandardCharsets.UTF_8), live.getBytes(StandardCharsets.UTF_8)), null);
            var stream = spy(new RedisMarketStream(adapter));
            var orders = mock(OrderRepository.class);
            when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenReturn(Set.of());
            var subscriptions = new OrderSubscriptionService(orders, stream);
            long amount = subscriptions.withQuoteSnapshot(code, q -> {
                subscriptions.assertQuoteSource(code, q);
                return new MarketOrderPricingService().estimate(code, Order.Side.BUY, 2, q.orderBook(), Instant.now()).amount();
            });
            assertThat(amount).isEqualTo(140200);
            verify(stream).readOrderBookSnapshot(code);
            assertThat(redis.opsForValue().get(key)).contains("70100");
            assertThat(adapter.readOrderBookSnapshot(code).orElseThrow().receivedAt()).isNull();
            redis.opsForValue().set(key, "invalid-json", Duration.ofMinutes(2));
            assertThatThrownBy(() -> subscriptions.withQuoteSnapshot(code, q ->
                    new MarketOrderPricingService().estimate(code, Order.Side.BUY, 2, q.orderBook(), Instant.now())))
                    .isInstanceOf(com.stock_spoon.river_be.order.exception.OrderException.class);
            redis.delete(key);
            assertThat(adapter.readOrderBookSnapshot(code)).isEmpty();
        } finally {
            adapter.close();
            if (owned) redis.delete(key);
            connection.destroy();
        }
    }
}
