package com.stock_spoon.river_be.market.kiwoom;

import com.stock_spoon.river_be.market.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.listener.*;
import static org.assertj.core.api.Assertions.*;

/** 로컬 관리 연결로 임시 ACL 사용자만 만들고 운영 저장소 코드를 제한된 인증 연결로 실행한다. */
@EnabledIfEnvironmentVariable(named = "REDIS_LIVE_TEST", matches = "true")
class RedisAclLiveTests {
    @Test
    void restrictedUserSupportsStoresLuaAndExplicitChannelsAndRejectsOtherAccess() throws Exception {
        var adminConnection = new LettuceConnectionFactory("localhost", 6380);
        adminConnection.afterPropertiesSet(); adminConnection.start();
        var admin = new StringRedisTemplate(adminConnection);
        String suffix = UUID.randomUUID().toString();
        String username = "stockspoon-development-" + suffix;
        String password = UUID.randomUUID() + "-" + UUID.randomUUID();
        String passwordHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(password.getBytes(StandardCharsets.UTF_8)));
        var standalone = new RedisStandaloneConfiguration("localhost", 6380);
        standalone.setUsername(username); standalone.setPassword(password);
        var restrictedConnection = new LettuceConnectionFactory(standalone);
        RedisCollectorHeartbeat owner = null;
        RedisMessageListenerContainer channels = null;
        boolean userCreated = false;
        boolean keysOwned = false;
        String tokenKey = "token:kiwoom:development-test:" + suffix;
        String sessionKey = "session:development:" + suffix;
        var keys = List.of("quote:TST010:price", "quote:TST010:orderbook", "quote:TST010:subscription",
                "quote:TST010:metadata", tokenKey, sessionKey);
        try {
            assertThat(admin.hasKey("coord:kiwoom:collector")).isFalse();
            for (String key : keys) assertThat(admin.hasKey(key)).as("기존 테스트 값 보호: %s", key).isFalse();
            var rules = new ArrayList<>(List.of("SETUSER", username, "reset", "on", "#" + passwordHash,
                    "~quote:*", "~session:*", "~token:*", "~coord:*", "&quotes:*", "-@all"));
            rules.addAll(List.of("+auth", "+hello", "+ping", "+client|setinfo", "+client|setname", "+client|id",
                    "+get", "+set", "+expire", "+pexpire", "+del", "+ttl", "+pttl",
                    "+eval", "+evalsha", "+script|load",
                    "+publish", "+subscribe", "+unsubscribe"));
            execute(admin, "ACL", rules.toArray(String[]::new));
            userCreated = true;
            restrictedConnection.afterPropertiesSet(); restrictedConnection.start();
            var restricted = new StringRedisTemplate(restrictedConnection);
            assertThat(restricted.execute((RedisCallback<String>) RedisConnection::ping)).isEqualTo("PONG");
            owner = new RedisCollectorHeartbeat(restricted, true);
            owner.start();
            keysOwned = true;
            owner.renew();
            assertThat(owner.ownsLease()).isTrue();
            assertThat(restricted.getExpire("coord:kiwoom:collector")).isBetween(1L, 15L);
            channels = new RedisMessageListenerContainer();
            channels.setConnectionFactory(restrictedConnection);
            channels.afterPropertiesSet(); channels.start();
            var subscribed = new CountDownLatch(1);
            var messages = new LinkedBlockingQueue<String>();
            class Listener implements MessageListener, SubscriptionListener {
                public void onMessage(Message message, byte[] pattern) {
                    messages.add(new String(message.getBody(), StandardCharsets.UTF_8));
                }
                public void onChannelSubscribed(byte[] channel, long count) { subscribed.countDown(); }
            }
            channels.addMessageListener(new Listener(), new ChannelTopic("quotes:TST010"));
            assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
            var quotes = new RedisQuoteStore(restricted);
            var source = new QuoteStore.Source(owner.ownerId(), suffix);
            quotes.register("TST010", source, Instant.now());
            var price = new KiwoomStockStream.StockPrice("TST010", new BigDecimal("119500"),
                    BigDecimal.ZERO, BigDecimal.ZERO, LocalTime.of(10, 0), Instant.now());
            quotes.savePrice(price, source);
            assertThat(messages.poll(5, TimeUnit.SECONDS)).contains("TST010");
            assertThat(quotes.usablePrice("TST010")).contains(price);
            var book = new KiwoomStockStream.OrderBook("TST010",
                    List.of(new KiwoomStockStream.QuoteLevel(new BigDecimal("119600"), 10)),
                    List.of(new KiwoomStockStream.QuoteLevel(new BigDecimal("119500"), 10)),
                    LocalTime.of(10, 0), Instant.now());
            quotes.saveOrderBook(book, source);
            assertThat(quotes.usableOrderBook("TST010")).contains(book);
            assertThat(restricted.getExpire("quote:TST010:price")).isBetween(86300L, 86400L);
            var metadata = new StockMetadataCache(restricted);
            assertThat(metadata.load("TST010", () -> Map.of("return_code", 0, "code", "TST010", "name", "TEST",
                    "marketCode", "0", "upName", "TEST"))).containsEntry("name", "TEST");
            var tokens = new RedisKiwoomTokenStore(restricted, "test-app", tokenKey);
            var token = new RedisKiwoomTokenStore.Token("dummy-token", Instant.now().plusSeconds(3600));
            tokens.save(token, Instant.now());
            assertThat(tokens.read()).contains(token);
            tokens.invalidate("dummy-token");
            assertThat(tokens.read()).isEmpty();
            restricted.opsForValue().set(sessionKey, "temporary", Duration.ofSeconds(60));
            assertThat(restricted.opsForValue().get(sessionKey)).isEqualTo("temporary");
            assertThat(execute(restricted, "SCRIPT", "LOAD", "return 1")).isNotNull();
            assertThat(restricted.execute(new DefaultRedisScript<>("return 1", Long.class), List.of(sessionKey))).isEqualTo(1L);

            assertDenied(() -> restricted.opsForValue().get("forbidden:development:" + suffix));
            assertDenied(() -> restricted.opsForValue().set("quotes:development:" + suffix, "blocked"));
            assertDenied(() -> restricted.convertAndSend("quote:TST010", "blocked"));
            assertDenied(() -> execute(restricted, "SUBSCRIBE", "quote:TST010"));
            assertDenied(() -> execute(restricted, "PSUBSCRIBE", "quotes:*"));
            assertDenied(() -> execute(restricted, "KEYS", "quote:*"));
            assertDenied(() -> execute(restricted, "ACL", "WHOAMI"));
            channels.stop();
            quotes.remove("TST010");
            owner.close();
            assertThat(admin.hasKey("coord:kiwoom:collector")).isFalse();
        } finally {
            if (channels != null) { channels.stop(); channels.destroy(); }
            if (owner != null) owner.close();
            restrictedConnection.destroy();
            if (keysOwned) for (String key : keys) admin.delete(key);
            if (userCreated) {
                try (var connection = adminConnection.getConnection()) {
                    // ACL DELUSER의 정수 응답에는 Lettuce의 정수 디코더를 지정한다.
                    Object removed = ((org.springframework.data.redis.connection.lettuce.LettuceConnection) connection).execute("ACL",
                            new io.lettuce.core.output.IntegerOutput<>(io.lettuce.core.codec.ByteArrayCodec.INSTANCE),
                            "DELUSER".getBytes(StandardCharsets.UTF_8), username.getBytes(StandardCharsets.UTF_8));
                    assertThat(removed).as("테스트 ACL 사용자 삭제").isEqualTo(1L);
                }
            }
            adminConnection.destroy();
        }
    }

    private static Object execute(StringRedisTemplate redis, String command, String... arguments) {
        byte[][] encoded = Arrays.stream(arguments).map(value -> value.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
        return redis.execute((RedisCallback<Object>) connection -> connection.execute(command, encoded));
    }
    private static void assertDenied(Runnable action) {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).as("허용하지 않은 접근은 실패해야 함").isNotNull();
        while (failure.getCause() != null) failure = failure.getCause();
        assertThat(failure.getMessage().contains("NOPERM")).as("ACL 권한 오류 여부").isTrue();
    }
}
