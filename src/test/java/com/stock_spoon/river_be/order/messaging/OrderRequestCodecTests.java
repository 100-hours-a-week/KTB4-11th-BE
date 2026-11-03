package com.stock_spoon.river_be.order.messaging;

import com.stock_spoon.river_be.order.entity.Order;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OrderRequestCodecTests {
    final OrderRequestCodec codec = new OrderRequestCodec(JsonMapper.builder().build());
    @Test void preservesAiReasonIdThroughDecodeAndEncode() {
        var message = reasonMessage("550e8400-e29b-41d4-a716-446655440000");
        assertThat(message.request().reasonId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        var encoded = JsonMapper.builder().build().readTree(codec.encode(message));
        assertThat(encoded.path("payload").path("reason_id").asText())
                .isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    }

    @Test void missingReasonIdRemainsOptionalAndDoesNotChangeLegacyPayloadShape() {
        var message = OrderRequestPolicyTests.message("market", null);
        assertThat(message.request().reasonId()).isNull();
        assertThat(JsonMapper.builder().build().readTree(codec.encode(message)).path("payload").has("reason_id")).isFalse();
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validateProperty(message.request(), "reasonId")).isEmpty();
        }
    }

    @Test void validatesReasonIdWhenPresent() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validateProperty(reasonMessage("550e8400-e29b-41d4-a716-446655440000").request(), "reasonId")).isEmpty();
            for (String invalid : java.util.List.of("", "not-a-uuid", "1-1-1-1-1")) {
                assertThat(validator.validateProperty(reasonMessage(invalid).request(), "reasonId")).hasSize(1);
            }
        }
    }

    private OrderRequestMessage reasonMessage(String reasonId) {
        return codec.decode("""
                {"eventId":"ai-reason-001","payload":{"account_id":2,"user_id":1,
                 "stock_code":"005930","order_side":"buy","order_type":"limit",
                 "limit_price":70000,"quantity":1,"reason":"test","holding_weight_limit_percent":10,
                 "reason_id":"%s"}}
                """.formatted(reasonId), Instant.parse("2026-10-22T01:00:00Z"));
    }
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
