package com.stock_spoon.river_be.market;

import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** 각 BE의 주문 처리가 사용하는 Redis 원본 읽기 경로. */
@Component
@Primary
@ConditionalOnProperty(name = "kiwoom.stream.enabled", havingValue = "true")
public class RedisMarketStream implements MarketStream {
    private final AiQuoteAdapter quotes;

    public RedisMarketStream(AiQuoteAdapter quotes) { this.quotes = quotes; }

    public void updateSymbols(Set<String> symbols) { quotes.updateSymbols(symbols); }
    public Optional<StockPrice> latest(String code) {
        return latestQuote(code).map(RedisQuoteStore.QuoteEvent::price).or(() -> quotes.latestPrice(code));
    }
    public Optional<OrderBook> latestOrderBook(String code) {
        return latestBookQuote(code).map(RedisQuoteStore.QuoteEvent::orderBook).or(() -> quotes.latestOrderBook(code));
    }
    public Optional<RedisQuoteStore.QuoteEvent> latestQuote(String code) { return quotes.latestQuote(code); }
    public Optional<RedisQuoteStore.QuoteEvent> latestBookQuote(String code) { return quotes.latestBookQuote(code); }
    public void assertAvailable(String code) { quotes.assertAvailable(code); }
    public void assertSource(String code, QuoteStore.Source source) { quotes.assertSource(code, source); }
    public void close() { quotes.close(); }
}
