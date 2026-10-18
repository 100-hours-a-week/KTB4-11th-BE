package com.stock_spoon.river_be.market;

import java.time.Instant;

public record QuoteEvent(String stockCode, String type, Instant receivedAt, QuoteSource source,
        StockPrice price, OrderBook orderBook) {}
