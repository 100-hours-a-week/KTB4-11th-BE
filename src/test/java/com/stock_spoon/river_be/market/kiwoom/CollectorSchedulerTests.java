package com.stock_spoon.river_be.market.kiwoom;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectorSchedulerTests {
    @Configuration @EnableScheduling static class Scheduling {}

    @Test
    void blockedDefaultSchedulerDoesNotBlockActualHeartbeatRenewal() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
        var heartbeat = new RedisCollectorHeartbeat(redis, true);
        when(values.get("coord:kiwoom:collector")).thenReturn(heartbeat.ownerId());
        var blocked = new AtomicBoolean();
        var renewed = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class)))
                .thenAnswer(call -> { if (blocked.get()) renewed.countDown(); return 1L; });
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Scheduling.class, CollectorSchedulingConfig.class);
            context.registerBean(RedisCollectorHeartbeat.class, () -> heartbeat);
            context.refresh();
            context.getBean("taskScheduler", TaskScheduler.class).schedule(() -> {
                blocked.set(true); started.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }, Instant.now());
            try {
                assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(renewed.await(7, TimeUnit.SECONDS)).isTrue();
                assertThat(heartbeat.ownsLease()).isTrue();
            } finally { release.countDown(); }
        }
    }
}
