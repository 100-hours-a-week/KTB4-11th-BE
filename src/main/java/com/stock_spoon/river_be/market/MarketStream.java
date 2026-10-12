package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.OrderBook;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.StockPrice;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** 주문이 사용하는 시세 경로. 운영에서는 공유 Redis 구현을 주입한다. */
public interface MarketStream {
    void updateSymbols(Set<String> symbols);
    CompletableFuture<Void> whenSubscribed(String symbol);
    Optional<StockPrice> latest(String symbol);
    default Optional<RedisQuoteStore.QuoteEvent> latestQuote(String symbol) {
        return latest(symbol).map(price -> new RedisQuoteStore.QuoteEvent(symbol, "price",
                price.receivedAt(), null, price, null));
    }
    Optional<OrderBook> latestOrderBook(String symbol);
    default Optional<RedisQuoteStore.QuoteEvent> latestBookQuote(String symbol) {
        return latestOrderBook(symbol).map(book -> new RedisQuoteStore.QuoteEvent(symbol, "orderbook",
                book.receivedAt(), null, null, book));
    }

    // 외부 Redis 없이 실행하는 단독 웹소켓 테스트에서는 임시 수요를 로컬로 유지한다.
    default void retainRequest(String id, String symbol) {}
    default void renewRequest(String id, String symbol) {}
    default void releaseRequest(String id, String symbol) {}
    default void assertRequest(String id, String symbol) {}
    default void assertAvailable(String symbol) {}
    default void assertSource(String symbol, QuoteStore.Source source) { assertAvailable(symbol); }
    default void assertRestFallbackAllowed() {}
}
