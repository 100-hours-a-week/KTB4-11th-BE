package com.stock_spoon.river_be.market;

import java.util.Optional;
import java.util.Set;

/** 주문이 사용하는 시세 경로. 운영에서는 공유 Redis 구현을 주입한다. */
public interface MarketStream {
    void updateSymbols(Set<String> symbols);
    Optional<StockPrice> latest(String symbol);
    default Optional<QuoteEvent> latestQuote(String symbol) {
        return latest(symbol).map(price -> new QuoteEvent(symbol, "price",
                price.receivedAt(), null, price, null));
    }
    Optional<OrderBook> latestOrderBook(String symbol);
    default Optional<QuoteEvent> latestBookQuote(String symbol) {
        return latestOrderBook(symbol).map(book -> new QuoteEvent(symbol, "orderbook",
                book.receivedAt(), null, null, book));
    }

    default void assertAvailable(String symbol) {}
    default void assertSource(String symbol, QuoteSource source) { assertAvailable(symbol); }
}
