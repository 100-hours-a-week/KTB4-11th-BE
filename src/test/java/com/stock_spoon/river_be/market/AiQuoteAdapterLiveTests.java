package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.market.kiwoom.RedisCollectorHeartbeat;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@EnabledIfEnvironmentVariable(named = "REDIS_LIVE_TEST", matches = "true")
class AiQuoteAdapterLiveTests {
    @Test void relaysRealAiPubSubAndInvalidatesSnapshotsOnReconnect() throws Exception {
        var connection = new LettuceConnectionFactory("localhost", 6380);
        connection.afterPropertiesSet(); connection.start();
        var redis = new StringRedisTemplate(connection);
        var store = new RedisQuoteStore(redis);
        var owner = new RedisCollectorHeartbeat(redis, true);
        var channels = new RedisMessageListenerContainer();
        channels.setConnectionFactory(connection); channels.afterPropertiesSet(); channels.start();
        var adapter = new AiQuoteAdapter(redis, store, owner, channels);
        String code = "TST011";
        var keys = List.of("price:" + code, "orderbook:" + code, "quote:" + code + ":price",
                "quote:" + code + ":orderbook", "quote:" + code + ":subscription");
        var events = new LinkedBlockingQueue<String>();
        var subscribed = new CountDownLatch(1);
        class Listener implements org.springframework.data.redis.connection.MessageListener,
                org.springframework.data.redis.connection.SubscriptionListener {
            public void onMessage(org.springframework.data.redis.connection.Message message, byte[] pattern) {
                events.add(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8));
            }
            public void onChannelSubscribed(byte[] channel, long count) { subscribed.countDown(); }
        }
        boolean owned = false;
        try {
            for (String key : keys) assertThat(redis.hasKey(key)).as(key).isFalse();
            assertThat(redis.hasKey("coord:kiwoom:collector")).isFalse();
            owner.start(); owned = true;
            channels.addMessageListener(new Listener(), new ChannelTopic("quotes:" + code));
            assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
            String timestamp = OffsetDateTime.now(ZoneOffset.ofHours(9)).withNano(0).toString();
            String payload = "{\"code\":\"TST011\",\"price\":70000,\"change\":-100,\"rate\":-0.14,\"volume\":100,\"timestamp\":\"" + timestamp + "\"}";
            redis.opsForValue().set("price:" + code, payload);
            assertThat(adapter.latestPrice(code).orElseThrow().receivedAt()).isNull();
            adapter.updateSymbols(Set.of(code));
            assertThat(events.poll(150, TimeUnit.MILLISECONDS)).isNull();
            redis.convertAndSend("price:" + code, payload);
            assertThat(events.poll(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(store.usablePrice(code).orElseThrow().exchangeTimestamp()).isEqualTo(OffsetDateTime.parse(timestamp).toInstant());
            String book = "{\"code\":\"TST011\",\"asks\":[{\"price\":70100,\"qty\":10}],\"bids\":[{\"price\":70000,\"qty\":20}],\"timestamp\":\"" + timestamp + "\"}";
            redis.opsForValue().set("orderbook:" + code, book);
            assertThat(adapter.latestOrderBook(code).orElseThrow().receivedAt()).isNull();
            redis.convertAndSend("orderbook:" + code, book);
            assertThat(events.poll(5, TimeUnit.SECONDS)).contains("orderbook");
            assertThat(store.usableOrderBook(code).orElseThrow().asks().getFirst().quantity()).isEqualTo(10);
            var source = store.activeSource(code).orElseThrow();
            channels.stop(); channels.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> store.activeSource(code).filter(source::equals).isEmpty());
            assertThat(store.usablePrice(code)).isEmpty();
            assertThat(store.usableOrderBook(code)).isEmpty();
            assertThat(events.poll(150, TimeUnit.MILLISECONDS)).isNull();
            assertThat(redis.opsForValue().get("price:" + code)).isEqualTo(payload);
            owner.close();
            adapter.updateSymbols(Set.of());
            assertThat(redis.opsForValue().get("price:" + code)).isEqualTo(payload);
        } finally {
            adapter.close(); channels.destroy();
            if (owned) { keys.forEach(redis::delete); owner.close(); }
            connection.destroy();
        }
    }
}
