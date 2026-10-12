package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "REDIS_LIVE_TEST", matches = "true")
class RedisSharedMarketLiveTests {
    @Test
    void sharesDemandQuotesAndExplicitChannelEventsBetweenTwoServers() throws Exception {
        var connection = new LettuceConnectionFactory("localhost", 6380);
        connection.afterPropertiesSet();
        connection.start();
        var redis = new StringRedisTemplate(connection);
        var store = new RedisQuoteStore(redis);
        var demands = new RedisSubscriptionDemand(redis);
        String id = java.util.UUID.randomUUID().toString();
        String code = "TST003";
        String owner = java.util.UUID.randomUUID().toString();
        String session = java.util.UUID.randomUUID().toString();
        var context = new QuoteStore.Source(owner, session);
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connection);
        container.afterPropertiesSet();
        container.start();
        var events = new LinkedBlockingQueue<KiwoomStockStream.StockPrice>();
        var stream = new RedisMarketStream(store, demands, mock(KiwoomStockStream.class),
                mock(com.stock_spoon.river_be.market.kiwoom.RedisCollectorHeartbeat.class), container,
                event -> { if (event instanceof RedisQuoteStore.QuoteEvent quote) events.add(quote.price()); },
                mock(com.stock_spoon.river_be.order.OrderRepository.class));
        try {
            assertThat(redis.hasKey("coord:kiwoom:collector")).isFalse();
            redis.opsForValue().set("coord:kiwoom:collector", owner, java.time.Duration.ofSeconds(15));
            demands.retain(id, code);
            assertThat(demands.symbols()).contains(code);
            assertThat(redis.getExpire(RedisSubscriptionDemand.requestKey(id))).isBetween(1L, 30L);
            stream.updateSymbols(Set.of(code));
            assertThat(stream.whenSubscribed(code)).isNotDone();
            store.register(code, context, Instant.now());
            stream.reconcile();
            assertThat(stream.whenSubscribed(code)).isCompleted();
            var price = new KiwoomStockStream.StockPrice(code, new BigDecimal("119500"),
                    BigDecimal.ZERO, BigDecimal.ZERO, LocalTime.of(10, 0), Instant.now());
            store.savePrice(price, context);
            assertThat(events.poll(3, TimeUnit.SECONDS)).isEqualTo(price);
            assertThat(stream.latest(code)).contains(price);

            // 알림 연결이 끊긴 동안 놓친 값은 재구독 ACK 이후 최신 캐시로만 복구한다.
            container.stop();
            events.clear();
            var missed = new KiwoomStockStream.StockPrice(code, new BigDecimal("120000"),
                    BigDecimal.ZERO, BigDecimal.ZERO, price.tradeTime(), Instant.now());
            store.savePrice(missed, context);
            assertThat(events).isEmpty();
            container.start();
            assertThat(events.poll(3, TimeUnit.SECONDS)).isEqualTo(missed);
            assertThat(stream.latest(code)).contains(missed);

            var next = new QuoteStore.Source(owner, "next-session");
            store.register(code, next, Instant.now());
            assertThat(stream.latest(code)).isEmpty(); // 이전 연결의 가격은 사용하지 않는다.
            store.savePrice(price, context); // 뒤늦게 도착한 이전 연결 메시지
            assertThat(stream.latest(code)).isEmpty();
            assertThat(events.poll(200, TimeUnit.MILLISECONDS)).isNull();
            redis.delete("coord:kiwoom:collector");
            assertThat(stream.latest(code)).isEmpty();
            assertThatThrownBy(() -> stream.assertAvailable(code)).isInstanceOf(IllegalStateException.class);
            demands.release(id, code);
            assertThat(demands.symbols()).doesNotContain(code);
        } finally {
            stream.updateSymbols(Set.of());
            stream.close();
            demands.release(id, code);
            store.remove(code);
            if (owner.equals(redis.opsForValue().get("coord:kiwoom:collector"))) redis.delete("coord:kiwoom:collector");
            container.destroy();
            connection.destroy();
        }
    }

    @Test
    void expiredRequestCannotBeRevivedAndOneRequestCannotRemoveAnother() {
        var connection = new LettuceConnectionFactory("localhost", 6380);
        connection.afterPropertiesSet();
        connection.start();
        var redis = new StringRedisTemplate(connection);
        var demands = new RedisSubscriptionDemand(redis);
        String first = java.util.UUID.randomUUID().toString();
        String second = java.util.UUID.randomUUID().toString();
        try {
            demands.retain(first, "TST004");
            demands.retain(second, "TST004");
            demands.release(first, "TST004");
            assertThat(demands.symbols()).contains("TST004");
            redis.delete(RedisSubscriptionDemand.requestKey(second));
            assertThatThrownBy(() -> demands.renew(second, "TST004")).isInstanceOf(IllegalStateException.class);
            assertThat(redis.hasKey(RedisSubscriptionDemand.requestKey(second))).isFalse();
            demands.release(second, "TST004");
            assertThat(demands.symbols()).doesNotContain("TST004");
        } finally {
            demands.release(first, "TST004");
            demands.release(second, "TST004");
            connection.destroy();
        }
    }
}
