package com.stock_spoon.river_be.execution.service;

import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.StockPrice;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiQuoteExecutionTests {
    private final Instant now = Instant.parse("2026-10-16T01:00:02Z");
    private final OrderExecutionService service = new OrderExecutionService(null, null, null, null,
            Clock.fixed(now, ZoneOffset.UTC));

    private StockPrice price(Instant exchange, Instant receipt) {
        return new StockPrice("005930", new BigDecimal("70000"), BigDecimal.ZERO, BigDecimal.ZERO,
                exchange.atZone(ZoneId.of("Asia/Seoul")).toLocalTime(), receipt, exchange, 100L);
    }

    @Test void validatesFullExchangeDateIndependentlyOfReceipt() {
        assertThat(service.isUsablePrice(price(now.minusSeconds(1), now))).isTrue();
        assertThat(service.isUsablePrice(price(now.minusSeconds(86400), now))).isFalse();
        assertThat(service.isUsablePrice(price(now.plusSeconds(2), now))).isFalse();
        assertThat(service.isUsablePrice(price(now.minusSeconds(6), now))).isFalse();
        assertThat(service.isUsablePrice(price(now.minusSeconds(1), null))).isFalse();
    }
}
