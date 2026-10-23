package com.stock_spoon.river_be.order.messaging;

import com.stock_spoon.river_be.order.entity.Order;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OrderRequestCodecTests {
    final OrderRequestCodec codec = new OrderRequestCodec(JsonMapper.builder().build());
    @Test void readsConfirmedAiEnvelopeWithoutInventingActorField() {
        var result=codec.decode("""
                {"eventId":"ai-order-001","payload":{"account_id":2,"user_id":1,
                 "stock_code":"005930","order_side":"buy","order_type":"limit",
                 "limit_price":70000,"quantity":1,"reason":"test","holding_weight_limit_percent":10}}
                """,Instant.parse("2026-10-22T01:00:00Z"));
        assertThat(result.source()).isEqualTo(Order.Source.AI);
        assertThat(result.accountId()).isEqualTo(2);
        assertThat(result.request().limitPrice()).isEqualTo(70000);
        assertThat(result.correlationId()).isEqualTo("ai-order-001");
    }
    @Test void backendEnvelopeRoundTripsUserIdentityAndOriginalRequestTime() {
        var message=OrderRequestPolicyTests.message("market",null);
        assertThat(codec.decode(codec.encode(message),message.requestedAt().plusSeconds(3600))).isEqualTo(message);
    }
    @Test void rejectsMissingAccountRatherThanDefaultingToZero() {
        assertThatThrownBy(()->codec.decode("{\"eventId\":\"x\",\"payload\":{}}",Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
