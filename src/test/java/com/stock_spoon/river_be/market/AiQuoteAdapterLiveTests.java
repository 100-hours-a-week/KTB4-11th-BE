package com.stock_spoon.river_be.market;

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
    @Test void twoIndependentBesReadOriginalChannelsWithoutRelayOrSharedState() throws Exception {
        var connection = new LettuceConnectionFactory("localhost", 6380);
        connection.afterPropertiesSet(); connection.start();
        var redis = new StringRedisTemplate(connection);
        var containers = new ArrayList<RedisMessageListenerContainer>();
        var adapters = new ArrayList<AiQuoteAdapter>();
        var first = new LinkedBlockingQueue<QuoteEvent>();
        var second = new LinkedBlockingQueue<QuoteEvent>();
        var relayed = new LinkedBlockingQueue<String>();
        String code = "TST019";
        String key = "price:" + code;
        String timestamp = OffsetDateTime.now(ZoneOffset.ofHours(9)).withNano(0).toString();
        String payload = "{\"code\":\"TST019\",\"price\":70000,\"change\":-100,\"rate\":-0.14,\"volume\":100,\"timestamp\":\"" + timestamp + "\"}";
        boolean owned = false;
        try {
            owned = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, payload, Duration.ofMinutes(1)));
            assertThat(owned).as("기존 원본 데이터 보호").isTrue();
            for (var queue : List.of(first, second)) {
                var channels = new RedisMessageListenerContainer();
                channels.setConnectionFactory(connection); channels.afterPropertiesSet(); channels.start();
                containers.add(channels);
                var adapter = new AiQuoteAdapter(redis, channels, event -> queue.add((QuoteEvent) event));
                adapters.add(adapter);
                assertThat(adapter.latestPrice(code).orElseThrow().receivedAt()).isNull();
                adapter.updateSymbols(Set.of(code));
            }
            var channels = containers.getFirst();
            channels.addMessageListener((message, pattern) -> relayed.add("unexpected"), new ChannelTopic("quotes:" + code));
            assertThat(first.poll(100, TimeUnit.MILLISECONDS)).isNull();
            redis.convertAndSend(key, payload);
            var a = first.poll(5, TimeUnit.SECONDS);
            var b = second.poll(5, TimeUnit.SECONDS);
            assertThat(a).isNotNull(); assertThat(b).isNotNull();
            assertThat(a.price()).isEqualToIgnoringGivenFields(b.price(), "receivedAt");
            assertThat(a.source().subscriberId()).isNotEqualTo(b.source().subscriberId());
            adapters.getFirst().assertSource(code, a.source());
            assertThatThrownBy(() -> adapters.get(1).assertSource(code, a.source())).isInstanceOf(IllegalStateException.class);
            String book = "{\"code\":\"TST019\",\"asks\":[{\"price\":70100,\"qty\":10}],\"bids\":[{\"price\":70000,\"qty\":20}],\"timestamp\":\"" + timestamp + "\"}";
            redis.convertAndSend("orderbook:" + code, book);
            assertThat(first.poll(5, TimeUnit.SECONDS).orderBook().asks().getFirst().quantity()).isEqualTo(10);
            assertThat(second.poll(5, TimeUnit.SECONDS).type()).isEqualTo("orderbook");
            channels.stop(); channels.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> adapters.getFirst().latestQuote(code).isEmpty());
            assertThatThrownBy(() -> adapters.getFirst().assertSource(code, a.source())).isInstanceOf(IllegalStateException.class);
            assertThat(adapters.getFirst().latestBookQuote(code)).isEmpty();
            assertThat(first.poll(150, TimeUnit.MILLISECONDS)).isNull();
            assertThat(relayed).isEmpty();
            assertThat(redis.opsForValue().get(key)).isEqualTo(payload);
            for (var adapter : adapters) adapter.updateSymbols(Set.of());
            assertThat(redis.opsForValue().get(key)).isEqualTo(payload);
        } finally {
            adapters.forEach(AiQuoteAdapter::close);
            for (var container : containers) container.destroy();
            if (owned) redis.delete(key);
            connection.destroy();
        }
    }
}
