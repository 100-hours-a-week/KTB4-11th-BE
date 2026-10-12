package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.OrderBook;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.StockPrice;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 최신 값을 보관한다. 주문 사용 시 연결·시각의 유효성은 호출 경로에서 별도로 검증한다. */
@Component
public class RedisQuoteStore implements QuoteStore {
    private static final Duration TTL = Duration.ofDays(1);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final StringRedisTemplate redis;

    public RedisQuoteStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void savePrice(StockPrice price) {
        savePrice(price, null);
    }

    public void saveOrderBook(OrderBook book) {
        saveOrderBook(book, null);
    }

    public record PriceSnapshot(Source source, StockPrice data) {}
    public record BookSnapshot(Source source, OrderBook data) {}
    public record Registration(Source source, Instant lastMessage) {}
    public record QuoteEvent(String stockCode, String type, Instant receivedAt, Source source,
            StockPrice price, OrderBook orderBook) {}

    public void savePrice(StockPrice price, Source source) {
        redis.opsForValue().set(key(price.stockCode(), "price"),
                JSON.writeValueAsString(new PriceSnapshot(source, price)), TTL);
        if (source != null) redis.convertAndSend("quotes:" + price.stockCode(),
                JSON.writeValueAsString(new QuoteEvent(price.stockCode(), "price", price.receivedAt(), source, price, null)));
    }

    public void saveOrderBook(OrderBook book, Source source) {
        redis.opsForValue().set(key(book.stockCode(), "orderbook"),
                JSON.writeValueAsString(new BookSnapshot(source, book)), TTL);
        if (source != null) redis.convertAndSend("quotes:" + book.stockCode(),
                JSON.writeValueAsString(new QuoteEvent(book.stockCode(), "orderbook", book.receivedAt(), source, null, book)));
    }

    public void register(String code, Source source, Instant lastMessage) {
        redis.opsForValue().set(key(code, "subscription"),
                JSON.writeValueAsString(new Registration(source, lastMessage)), Duration.ofSeconds(90));
    }

    public Optional<StockPrice> latestPrice(String stockCode) {
        String value = redis.opsForValue().get(key(stockCode, "price"));
        return value == null ? Optional.empty() : Optional.of(JSON.readValue(value, PriceSnapshot.class).data());
    }

    public Optional<OrderBook> latestOrderBook(String stockCode) {
        String value = redis.opsForValue().get(key(stockCode, "orderbook"));
        return value == null ? Optional.empty() : Optional.of(JSON.readValue(value, BookSnapshot.class).data());
    }

    /** JSON 형식과 TTL과는 별도로 현재 담당·연결 세션을 확인한다. */
    public Optional<Source> activeSource(String code) {
        String owner = redis.opsForValue().get("coord:kiwoom:collector");
        if (owner == null) return Optional.empty();
        String state = redis.opsForValue().get(key(code, "subscription"));
        if (state == null) return Optional.empty();
        var registration = JSON.readValue(state, Registration.class);
        if (registration.source() == null || !owner.equals(registration.source().collectorId())
                || !Instant.now().isBefore(registration.lastMessage().plusSeconds(90))) return Optional.empty();
        return Optional.of(registration.source());
    }

    public Optional<StockPrice> usablePrice(String code) {
        return usablePriceEvent(code).map(QuoteEvent::price);
    }

    public Optional<QuoteEvent> usablePriceEvent(String code) {
        var source = activeSource(code);
        if (source.isEmpty()) return Optional.empty();
        String value = redis.opsForValue().get(key(code, "price"));
        if (value == null) return Optional.empty();
        var snapshot = JSON.readValue(value, PriceSnapshot.class);
        return source.get().equals(snapshot.source()) && activeSource(code).equals(source)
                ? Optional.of(new QuoteEvent(code, "price", snapshot.data().receivedAt(), snapshot.source(), snapshot.data(), null))
                : Optional.empty();
    }

    public Optional<OrderBook> usableOrderBook(String code) {
        return usableBookEvent(code).map(QuoteEvent::orderBook);
    }

    public Optional<QuoteEvent> usableBookEvent(String code) {
        var source = activeSource(code);
        if (source.isEmpty()) return Optional.empty();
        String value = redis.opsForValue().get(key(code, "orderbook"));
        if (value == null) return Optional.empty();
        var snapshot = JSON.readValue(value, BookSnapshot.class);
        return source.get().equals(snapshot.source()) && activeSource(code).equals(source)
                ? Optional.of(new QuoteEvent(code, "orderbook", snapshot.data().receivedAt(), snapshot.source(), null, snapshot.data()))
                : Optional.empty();
    }

    public void remove(String stockCode) {
        // Serverless의 다중 키 제약을 피하도록 각 키를 따로 삭제한다.
        redis.delete(key(stockCode, "price"));
        redis.delete(key(stockCode, "orderbook"));
        redis.delete(key(stockCode, "subscription"));
    }

    private static String key(String stockCode, String kind) {
        if (stockCode == null || !stockCode.matches("[0-9A-Z]{6}")) {
            throw new IllegalArgumentException("종목코드를 확인하세요.");
        }
        return "quote:" + stockCode + ":" + kind;
    }
}
