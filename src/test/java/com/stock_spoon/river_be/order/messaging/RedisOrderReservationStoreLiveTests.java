package com.stock_spoon.river_be.order.messaging;

import com.stock_spoon.river_be.order.reservation.OrderReservationStore.Reservation;
import com.stock_spoon.river_be.order.reservation.RedisOrderReservationStore;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="ORDER_RESERVATION_REDIS_LIVE",matches="true")
class RedisOrderReservationStoreLiveTests {
    @Test void persistsReservationAndNeverResurrectsDeletedEntryAfterPublication() {
        var connection=new LettuceConnectionFactory("127.0.0.1",16380);
        connection.afterPropertiesSet(); connection.start();
        var redis=new StringRedisTemplate(connection);
        var store=new RedisOrderReservationStore(redis,JsonMapper.builder().build());
        var original=OrderRequestPolicyTests.message("limit",70000L);
        long account=System.nanoTime();
        var m=new OrderRequestMessage(original.eventId(),original.correlationId(),original.userId(),account,
                original.source(),original.requestedAt(),original.request());
        var reservation=new Reservation(m,70000,null);
        try {
            store.add(reservation);
            assertThat(store.list(account)).containsExactly(reservation);
            assertThat(store.accounts()).contains(account);
            assertThatThrownBy(()->store.add(reservation)).isInstanceOf(IllegalStateException.class);
            store.published(reservation,Instant.parse("2026-10-22T01:01:00Z"));
            assertThat(store.list(account).getFirst().publishedAt()).isEqualTo(Instant.parse("2026-10-22T01:01:00Z"));
            store.remove(account,m.eventId());
            store.published(reservation,Instant.now());
            assertThat(store.list(account)).isEmpty();
            assertThat(store.accounts()).doesNotContain(account);
        } finally { store.remove(account,m.eventId()); connection.destroy(); }
    }
}
