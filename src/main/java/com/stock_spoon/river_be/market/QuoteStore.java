package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.OrderBook;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.StockPrice;
import java.util.Optional;

public interface QuoteStore {
    void savePrice(StockPrice price);
    void saveOrderBook(OrderBook book);
    Optional<StockPrice> latestPrice(String stockCode);
    Optional<OrderBook> latestOrderBook(String stockCode);
    void remove(String stockCode);

    record Source(String collectorId, String sessionId) {}
    default void register(String stockCode, Source source, java.time.Instant lastMessage) {}
    default void savePrice(StockPrice price, Source source) { savePrice(price); }
    default void saveOrderBook(OrderBook book, Source source) { saveOrderBook(book); }
}
