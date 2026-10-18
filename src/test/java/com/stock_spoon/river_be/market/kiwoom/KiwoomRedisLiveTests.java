package com.stock_spoon.river_be.market.kiwoom;

import com.stock_spoon.river_be.order.repository.OrderRepository;

import com.stock_spoon.river_be.market.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 읽기 전용 키움 연결. 기존 담당과 시세 키를 덮어쓰지 않는다. 토큰은 재사용용으로 남긴다. */
@EnabledIfEnvironmentVariable(named = "KIWOOM_REDIS_LIVE_TEST", matches = "true")
class KiwoomRedisLiveTests {
    @Test
    void collectorUsesSharedTokenAndSharesRealRestAndWebSocketData() throws Exception {
        var settings = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(".env.local"))) { settings.load(reader); }
        String appKey = setting("KIWOOM_APP_KEY", settings);
        String appSecret = setting("KIWOOM_APP_SECRET", settings);
        assertThat(!appKey.isBlank() && !appSecret.isBlank()).as("키움 인증 설정 존재 여부").isTrue();
        var connections = new LettuceConnectionFactory("localhost", 6380);
        connections.afterPropertiesSet(); connections.start();
        var redis = new StringRedisTemplate(connections);
        var owner = new RedisCollectorHeartbeat(redis, true);
        var leaseClock = Executors.newSingleThreadScheduledExecutor();
        KiwoomStockStream collector = null;
        KiwoomStockStream disabledStream = null;
        RedisMessageListenerContainer channels = null;
        boolean acquired = false;
        boolean keysOwned = false;
        var testKeys = List.of("quote:005930:price", "quote:005930:orderbook", "quote:005930:subscription",
                "quote:005930:metadata");
        try {
            assertThat(redis.hasKey("coord:kiwoom:collector")).as("실행 중인 담당이 없어야 함").isFalse();
            for (String key : testKeys) assertThat(redis.hasKey(key)).as("기존 키 보호: %s", key).isFalse();
            owner.start(); acquired = true;
            // 담당 확보 직전 다른 프로세스가 값을 만들었으면 그 값도 보호한다.
            for (String key : testKeys) assertThat(redis.hasKey(key)).as("담당 확보 후 기존 키 보호: %s", key).isFalse();
            keysOwned = true;
            leaseClock.scheduleWithFixedDelay(owner::renew, 5, 5, TimeUnit.SECONDS);
            var config = new KiwoomConfig();
            var tokens = config.sharedKiwoomTokenProvider(appKey, appSecret, redis, owner);
            String token = tokens.accessToken();
            var reused = config.sharedKiwoomTokenProvider(appKey, appSecret, redis, owner);
            assertThat(token.equals(reused.accessToken())).as("새 제공자가 Redis 토큰을 재사용").isTrue();
            assertThat(redis.getExpire("token:kiwoom:access")).isPositive();
            var normalOwner = new RedisCollectorHeartbeat(redis, false);
            var normalTokens = config.sharedKiwoomTokenProvider("", "", redis, normalOwner);
            assertThatThrownBy(normalTokens::accessToken).isInstanceOf(IllegalStateException.class);
            System.out.println("LIVE CHECK: Redis 토큰 저장·재사용, 일반 BE 토큰 접근 거절 확인");

            var transport = config.kiwoomRestTransport(tokens, owner, redis, true, "", "");
            var metadata = new StockMetadataCache(redis);
            var market = new KiwoomMarketClient(transport, metadata);
            assertThat(market.currentPrice("005930")).isPositive();
            assertThat(market.stockInfo("005930").marketCode()).isEqualTo("0");
            assertThat(market.stockDetails("005930").stockName()).isNotBlank();
            var otherCache = new StockMetadataCache(redis);
            assertThat(otherCache.load("005930", () -> { throw new AssertionError("캐시 재조회 시 외부 호출 금지"); }))
                    .containsEntry("code", "005930");
            assertThat(redis.getExpire("quote:005930:metadata")).isBetween(604700L, 604800L);
            var store = new RedisQuoteStore(redis);
            collector = config.kiwoomStockStream(tokens, true, true, store, owner);
            disabledStream = config.kiwoomStockStream(normalTokens, true, false, store, normalOwner);
            var received = new LinkedBlockingQueue<RedisQuoteStore.QuoteEvent>();
            channels = new RedisMessageListenerContainer();
            channels.setConnectionFactory(connections); channels.afterPropertiesSet(); channels.start();
            channels.addMessageListener((message, pattern) -> {
                var quote = tools.jackson.databind.json.JsonMapper.builder().build()
                        .readValue(message.getBody(), RedisQuoteStore.QuoteEvent.class);
                if (store.activeSource(quote.stockCode()).filter(quote.source()::equals).isPresent()) received.add(quote);
            }, new org.springframework.data.redis.listener.ChannelTopic("quotes:005930"));
            collector.updateSymbols(Set.of("005930"));
            collector.whenSubscribed("005930").get(25, TimeUnit.SECONDS);
            assertThat(collector.state()).isEqualTo(KiwoomStockStream.State.SUBSCRIBED);
            System.out.println("LIVE CHECK: 실제 WebSocket LOGIN/REG 성공");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            var messageTimes = new HashSet<Instant>();
            while (System.nanoTime() < deadline && (messageTimes.size() < 2 || store.usableOrderBook("005930").isEmpty())) {
                var event = received.poll(500, TimeUnit.MILLISECONDS);
                if (event != null) messageTimes.add(event.receivedAt());
            }
            assertThat(messageTimes.size()).as("서로 다른 시각의 실제 현재가 알림 2회 이상").isGreaterThanOrEqualTo(2);
            assertThat(store.usablePrice("005930")).isPresent();
            assertThat(store.usableOrderBook("005930")).isPresent();
            assertThat(redis.getExpire("quote:005930:price")).isBetween(86300L, 86400L);
            assertThat(redis.getExpire("quote:005930:orderbook")).isBetween(86300L, 86400L);
            System.out.println("LIVE CHECK: 실제 현재가·호가 Redis 저장, 단독 키움 검증 경로의 Pub/Sub 수신 확인");
            var previousSource = store.activeSource("005930").orElseThrow();
            collector.close();
            assertThat(store.usablePrice("005930")).isEmpty();
            received.clear();
            collector = config.kiwoomStockStream(tokens, true, true, store, owner);
            collector.updateSymbols(Set.of("005930"));
            collector.whenSubscribed("005930").get(25, TimeUnit.SECONDS);
            assertThat(collector.state()).isEqualTo(KiwoomStockStream.State.SUBSCRIBED);
            var nextSource = store.activeSource("005930").orElseThrow();
            assertThat(nextSource.collectorId()).isEqualTo(previousSource.collectorId());
            assertThat(nextSource.sessionId()).isNotEqualTo(previousSource.sessionId());
            assertThat(store.activeSource("005930").filter(previousSource::equals)).isEmpty();
            long recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            boolean newPrice = false;
            while (System.nanoTime() < recoveryDeadline && (!newPrice || store.usableOrderBook("005930").isEmpty())) {
                var event = received.poll(500, TimeUnit.MILLISECONDS);
                if (event != null && nextSource.equals(event.source())) newPrice = true;
            }
            assertThat(newPrice).as("재연결의 실제 현재가 알림 수신").isTrue();
            assertThat(store.usableOrderBook("005930")).isPresent();
            System.out.println("LIVE CHECK: 실제 WebSocket 종료·새 LOGIN/REG·새 시세 수신, 이전 연결 출처 거절 확인");
            collector.close();
            owner.close();
            var nextOwner = new RedisCollectorHeartbeat(redis, true);
            nextOwner.start();
            try {
                assertThat(nextOwner.ownerId()).isNotEqualTo(owner.ownerId());
                var restarted = config.sharedKiwoomTokenProvider(appKey, appSecret, redis, nextOwner);
                assertThat(token.equals(restarted.accessToken())).as("새 담당 ID에서도 토큰 재사용").isTrue();
                System.out.println("LIVE CHECK: 담당 종료·새 담당 인스턴스의 Redis 토큰 재사용 확인");
            } finally { nextOwner.close(); }
        } finally {
            if (collector != null) collector.close();
            if (channels != null) { channels.stop(); channels.destroy(); }
            if (disabledStream != null) disabledStream.close();
            leaseClock.shutdownNow();
            if (acquired) owner.close();
            if (keysOwned) for (String key : testKeys) redis.delete(key);
            connections.destroy();
        }
    }

    private String setting(String name, Properties values) {
        return System.getenv(name) == null ? values.getProperty(name, "") : System.getenv(name);
    }
}
