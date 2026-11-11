package com.stock_spoon.river_be.market;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

public record OrderBook(String stockCode, List<QuoteLevel> asks, List<QuoteLevel> bids,
        LocalTime quoteTime, Instant receivedAt, Instant exchangeTimestamp, boolean directSnapshot) {
    public OrderBook(String stockCode, List<QuoteLevel> asks, List<QuoteLevel> bids,
            LocalTime quoteTime, Instant receivedAt, Instant exchangeTimestamp) {
        this(stockCode, asks, bids, quoteTime, receivedAt, exchangeTimestamp, false);
    }
    public OrderBook(String stockCode, List<QuoteLevel> asks, List<QuoteLevel> bids,
            LocalTime quoteTime, Instant receivedAt) {
        this(stockCode, asks, bids, quoteTime, receivedAt, null);
    }
    public OrderBook {
        asks = List.copyOf(asks);
        bids = List.copyOf(bids);
    }
}
