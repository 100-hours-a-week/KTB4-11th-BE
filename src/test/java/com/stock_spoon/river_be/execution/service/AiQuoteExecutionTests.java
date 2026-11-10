package com.stock_spoon.river_be.execution.service;

import com.stock_spoon.river_be.market.StockPrice;
import com.stock_spoon.river_be.market.OrderBook;
import java.util.List;
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

    @Test void validatesReceiptTimeIndependentlyOfSourceTimestamp() {
        assertThat(service.isUsablePrice(price(now.minusSeconds(86400), now))).isTrue();
        assertThat(service.isUsablePrice(price(now.plusSeconds(60), now))).isTrue();
        assertThat(service.isUsablePrice(price(now, now.minusSeconds(5)))).isTrue();
        assertThat(service.isUsablePrice(price(now, now.minusSeconds(6)))).isFalse();
        assertThat(service.isUsablePrice(price(now, now.plusSeconds(1)))).isFalse();
        assertThat(service.isUsablePrice(price(now, null))).isFalse();
    }

    @Test void bookFreshnessUsesReceiptTimeOnly() {
        var book = new OrderBook("005930", List.of(), List.of(), LocalTime.NOON,
                now.minusSeconds(5), now.minusSeconds(86400));
        assertThatCode(() -> MarketOrderPricingService.validateBook("005930", book, now)).doesNotThrowAnyException();
        assertThatThrownBy(() -> MarketOrderPricingService.validateBook("005930", book, now.plusSeconds(1)))
                .isInstanceOf(RuntimeException.class);
    }
}
