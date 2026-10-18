package com.stock_spoon.river_be.market;

import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "kiwoom.stream.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledMarketStream implements MarketStream {
    public void updateSymbols(Set<String> symbols) {}
    public Optional<StockPrice> latest(String symbol) { return Optional.empty(); }
    public Optional<OrderBook> latestOrderBook(String symbol) { return Optional.empty(); }
    public void assertAvailable(String symbol) {
        throw new IllegalStateException("실시간 시세 구독이 비활성화되어 있습니다.");
    }
}
