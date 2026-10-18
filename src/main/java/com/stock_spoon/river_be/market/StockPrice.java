package com.stock_spoon.river_be.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;

public record StockPrice(String stockCode, BigDecimal currentPrice, BigDecimal change,
        BigDecimal changeRate, LocalTime tradeTime, Instant receivedAt,
        Instant exchangeTimestamp, Long volume) {
    public StockPrice(String stockCode, BigDecimal currentPrice, BigDecimal change,
            BigDecimal changeRate, LocalTime tradeTime, Instant receivedAt) {
        this(stockCode, currentPrice, change, changeRate, tradeTime, receivedAt, null, null);
    }
}
