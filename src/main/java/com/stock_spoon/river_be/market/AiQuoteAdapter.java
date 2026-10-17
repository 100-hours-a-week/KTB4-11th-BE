package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.StockPrice;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.OrderBook;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.QuoteLevel;
import com.stock_spoon.river_be.market.kiwoom.RedisCollectorHeartbeat;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AI 키는 읽기 전용이다. BE 담당이 실제 수신한 알림만 BE 세션을 붙여 중계한다. */
@Component
@ConditionalOnProperty(name = "kiwoom.stream.enabled", havingValue = "true")
public class AiQuoteAdapter implements MessageListener, SubscriptionListener {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiQuoteAdapter.class);
    private final StringRedisTemplate redis;
    private final RedisQuoteStore quotes;
    private final RedisCollectorHeartbeat owner;
    private final RedisMessageListenerContainer channels;
    private final Clock clock;
    private final Object subscriptionLock = new Object();
    private final Map<String, QuoteStore.Source> sessions = new HashMap<>();
    private final Map<String, Set<String>> acknowledged = new HashMap<>();
    private final Map<String, StockPrice> prices = new HashMap<>();
    private final Map<String, OrderBook> books = new HashMap<>();
    private Set<String> desired = Set.of();

    @Autowired
    public AiQuoteAdapter(StringRedisTemplate redis, RedisQuoteStore quotes, RedisCollectorHeartbeat owner,
            @Qualifier("quoteListenerContainer") RedisMessageListenerContainer channels) {
        this(redis, quotes, owner, channels, Clock.systemUTC());
    }

    AiQuoteAdapter(StringRedisTemplate redis, RedisQuoteStore quotes, RedisCollectorHeartbeat owner,
            RedisMessageListenerContainer channels, Clock clock) {
        this.redis = redis;
        this.quotes = quotes;
        this.owner = owner;
        this.channels = channels;
        this.clock = clock;
    }

    public void updateSymbols(Set<String> symbols) {
        symbols.forEach(AiQuoteAdapter::validateCode);
        synchronized (subscriptionLock) {
            Set<String> next = owner.ownsLease() ? Set.copyOf(symbols) : Set.of();
            Set<String> previous;
            synchronized (this) {
                previous = desired;
                var retained = new HashSet<>(previous);
                retained.retainAll(next);
                desired = Set.copyOf(retained);
                for (String code : previous) if (!next.contains(code)) {
                    sessions.remove(code);
                    acknowledged.remove(code);
                    prices.remove(code);
                    books.remove(code);
                }
            }
            // 채널 등록은 ACK를 기다릴 수 있으므로 수신 처리 잠금을 잡지 않는다.
            for (String code : previous) if (!next.contains(code)) {
                channels.removeMessageListener(this, topics(code));
                if (owner.ownsLease()) quotes.remove(code);
            }
            for (String code : next) if (!previous.contains(code)) {
                synchronized (this) {
                    var retained = new HashSet<>(desired);
                    retained.add(code);
                    desired = Set.copyOf(retained);
                }
                try { channels.addMessageListener(this, topics(code)); }
                catch (RuntimeException error) {
                    synchronized (this) {
                        var retained = new HashSet<>(desired);
                        retained.remove(code);
                        desired = Set.copyOf(retained);
                        sessions.remove(code);
                        acknowledged.remove(code);
                    }
                    throw error;
                }
            }
        }
    }

    private static List<ChannelTopic> topics(String code) {
        return List.of(new ChannelTopic("price:" + code), new ChannelTopic("orderbook:" + code));
    }

    @Override
    public synchronized void onChannelSubscribed(byte[] channel, long count) {
        String code = channelCode(channel);
        if (code == null || !desired.contains(code) || !owner.ownsLease()) return;
        // 재연결 ACK는 이전 스냅샷을 새 시세로 발행하지 않는다.
        String topic = new String(channel, StandardCharsets.UTF_8);
        Set<String> ack = acknowledged.computeIfAbsent(code, ignored -> new HashSet<>());
        if (ack.isEmpty() || ack.contains(topic)) {
            ack.clear();
            var source = new QuoteStore.Source(owner.ownerId(), UUID.randomUUID().toString());
            sessions.put(code, source);
            quotes.register(code, source, clock.instant());
        }
        ack.add(topic);
    }

    @Override
    public synchronized void onMessage(Message message, byte[] pattern) {
        String code = channelCode(message.getChannel());
        if (code == null || !desired.contains(code) || !owner.ownsLease()) return;
        var source = sessions.get(code);
        if (source == null) return;
        Instant receivedAt = clock.instant();
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            var node = JSON.readTree(message.getBody());
            if (channel.startsWith("price:")) {
                var price = price(node, code, receivedAt);
                requireLive(price.exchangeTimestamp(), receivedAt);
                var previous = prices.get(code);
                if (previous != null && (price.exchangeTimestamp().isBefore(previous.exchangeTimestamp())
                        || price.exchangeTimestamp().atZone(SEOUL).toLocalDate().equals(previous.exchangeTimestamp().atZone(SEOUL).toLocalDate())
                        && price.volume() < previous.volume()
                        || price.exchangeTimestamp().equals(previous.exchangeTimestamp()) && price.volume().equals(previous.volume()))) return;
                if (!owner.ownsLease()) return;
                quotes.register(code, source, receivedAt);
                quotes.savePrice(price, source);
                prices.put(code, price);
            } else {
                var book = book(node, code, receivedAt);
                requireLive(book.exchangeTimestamp(), receivedAt);
                var previous = books.get(code);
                if (previous != null && (book.exchangeTimestamp().isBefore(previous.exchangeTimestamp())
                        || book.exchangeTimestamp().equals(previous.exchangeTimestamp())
                        && book.asks().equals(previous.asks()) && book.bids().equals(previous.bids()))) return;
                if (!owner.ownsLease()) return;
                quotes.register(code, source, receivedAt);
                quotes.saveOrderBook(book, source);
                books.put(code, book);
            }
        } catch (RuntimeException error) {
            log.warn("event=ai_quote_rejected stockCode={} causeType={}", code, error.getClass().getSimpleName());
        }
    }

    /** 스냅샷 GET에는 수신시각이 없다. 실행용 QuoteEvent로 승격하지 않는다. */
    public Optional<StockPrice> latestPrice(String code) {
        validateCode(code);
        String value = redis.opsForValue().get("price:" + code);
        if (value == null) return Optional.empty();
        try { return Optional.of(price(JSON.readTree(value), code, null)); }
        catch (RuntimeException error) { return Optional.empty(); }
    }

    public Optional<OrderBook> latestOrderBook(String code) {
        validateCode(code);
        String value = redis.opsForValue().get("orderbook:" + code);
        if (value == null) return Optional.empty();
        try { return Optional.of(book(JSON.readTree(value), code, null)); }
        catch (RuntimeException error) { return Optional.empty(); }
    }

    private static StockPrice price(JsonNode node, String code, Instant receivedAt) {
        Instant exchange = timestamp(node, code);
        BigDecimal value = number(node, "price");
        if (value.longValueExact() <= 0) throw new IllegalArgumentException("현재가 오류");
        long volume = number(node, "volume").longValueExact();
        if (volume < 0) throw new IllegalArgumentException("거래량 오류");
        return new StockPrice(code, value, number(node, "change"), number(node, "rate"),
                exchange.atZone(SEOUL).toLocalTime(), receivedAt, exchange, volume);
    }

    private static OrderBook book(JsonNode node, String code, Instant receivedAt) {
        Instant exchange = timestamp(node, code);
        return new OrderBook(code, levels(node.path("asks")), levels(node.path("bids")),
                exchange.atZone(SEOUL).toLocalTime(), receivedAt, exchange);
    }

    private static List<QuoteLevel> levels(JsonNode array) {
        if (!array.isArray() || array.isEmpty() || array.size() > 10) throw new IllegalArgumentException("호가 오류");
        var result = new ArrayList<QuoteLevel>();
        for (var level : array) {
            BigDecimal price = number(level, "price");
            long value = price.longValueExact();
            long quantity = number(level, "qty").longValueExact();
            if (value < 0 || quantity < 0 || value == 0 && quantity > 0) throw new IllegalArgumentException("호가 오류");
            result.add(new QuoteLevel(price, quantity));
        }
        return result;
    }

    private static BigDecimal number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) throw new IllegalArgumentException("필수 숫자 필드 오류");
        return new BigDecimal(value.asText());
    }

    private static Instant timestamp(JsonNode node, String code) {
        if (!node.isObject() || !code.equals(node.path("code").asText())) throw new IllegalArgumentException("종목 불일치");
        return OffsetDateTime.parse(node.path("timestamp").asText()).toInstant();
    }

    private static void requireLive(Instant exchange, Instant receivedAt) {
        if (!exchange.atZone(SEOUL).toLocalDate().equals(receivedAt.atZone(SEOUL).toLocalDate())
                || exchange.isBefore(receivedAt.minusSeconds(5)) || exchange.isAfter(receivedAt.plusSeconds(1))) {
            throw new IllegalArgumentException("지연되거나 미래인 시세");
        }
    }

    private static String channelCode(byte[] channel) {
        String value = new String(channel, StandardCharsets.UTF_8);
        if (!value.matches("(?:price|orderbook):[0-9A-Z]{6}")) return null;
        return value.substring(value.indexOf(':') + 1);
    }

    private static void validateCode(String code) {
        if (code == null || !code.matches("[0-9A-Z]{6}")) throw new IllegalArgumentException("종목코드를 확인하세요.");
    }

    @PreDestroy
    public void close() {
        synchronized (subscriptionLock) {
            synchronized (this) {
                desired = Set.of();
                sessions.clear();
                acknowledged.clear();
                prices.clear();
                books.clear();
            }
            channels.removeMessageListener(this);
        }
    }
}
