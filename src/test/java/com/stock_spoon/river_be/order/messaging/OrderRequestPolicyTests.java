package com.stock_spoon.river_be.order.messaging;

import com.stock_spoon.river_be.execution.service.MarketOrderPricingService;
import com.stock_spoon.river_be.market.OrderBook;
import com.stock_spoon.river_be.market.QuoteLevel;
import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.entity.Order;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OrderRequestPolicyTests {
    static final Instant NOW = Instant.parse("2026-10-22T01:00:00Z");
    @Test void marketBuyUsesAllAskLevelsAndRejectsPartialLiquidity() {
        var book = new OrderBook("005930", List.of(level(110, 2), level(100, 1)),
                List.of(level(90, 3)), LocalTime.of(10, 0), NOW);
        var pricing = new MarketOrderPricingService();
        assertThat(pricing.estimate("005930", Order.Side.BUY, 3, book, NOW).amount()).isEqualTo(320);
        assertThat(pricing.estimate("005930", Order.Side.SELL, 3, book, NOW).amount()).isEqualTo(270);
        assertThatThrownBy(() -> pricing.estimate("005930", Order.Side.BUY, 4, book, NOW))
                .hasMessageContaining("잔량");
        assertThatThrownBy(() -> pricing.estimate("005930", Order.Side.BUY, 1, book, NOW.plusSeconds(6)))
                .hasMessageContaining("호가");
    }
    @Test void marketExpiresAtSessionCloseAndNeverRevivesTomorrow() {
        var message = message("market", null);
        assertThat(message.expired(Instant.parse("2026-10-22T06:29:59Z"))).isFalse();
        assertThat(message.expired(Instant.parse("2026-10-22T06:30:00Z"))).isTrue();
        assertThat(message.expired(NOW.plusSeconds(86400))).isTrue();
        assertThat(message("limit", 100L).expired(NOW.plusSeconds(86400))).isFalse();
    }
    @Test void reusedIdCannotChangeOrderPayloadOrOwner() {
        var message = message("limit", 100L);
        assertThat(message.sameRequest(message("limit", 100L))).isTrue();
        assertThat(message.sameRequest(message("limit", 200L))).isFalse();
    }
    static OrderRequestMessage message(String type, Long price) {
        return new OrderRequestMessage("a4042d7e-70fa-4bfd-ac44-6611f162447f", "trace", 1, 2,
                Order.Source.USER, NOW, new OrderCreateRequest("005930", "buy", type, price,
                3L, null, null, List.of(), null, null));
    }
    static QuoteLevel level(long price, long qty) { return new QuoteLevel(BigDecimal.valueOf(price), qty); }
}
