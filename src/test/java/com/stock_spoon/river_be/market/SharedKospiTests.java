package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.market.kiwoom.RedisCollectorHeartbeat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SharedKospiTests {
    @Test
    void normalBeOnlyReadsRedisAndNeverRefreshes() {
        var market = mock(KiwoomMarketClient.class);
        var cache = mock(RedisKospiStore.class);
        var heartbeat = mock(RedisCollectorHeartbeat.class);
        var service = new KospiIndexService(market, true, cache, heartbeat,
                Clock.fixed(Instant.parse("2026-10-12T00:30:00Z"), ZoneOffset.UTC));
        when(cache.read(true)).thenReturn(Optional.empty());
        service.scheduledRefresh();
        service.refresh();
        assertThat(service.latest()).isEmpty();
        verifyNoInteractions(market);
        verify(cache).read(true);
        verify(cache, never()).read(false);
    }

    @Test
    void outsideHoursReadsLastSuccessAndLostCollectorDoesNotWrite() {
        var market = mock(KiwoomMarketClient.class);
        var cache = mock(RedisKospiStore.class);
        var heartbeat = mock(RedisCollectorHeartbeat.class);
        var service = new KospiIndexService(market, true, cache, heartbeat,
                Clock.fixed(Instant.parse("2026-10-12T07:00:00Z"), ZoneOffset.UTC));
        when(cache.read(false)).thenReturn(Optional.empty());
        assertThat(service.latest()).isEmpty();
        when(heartbeat.ownsLease()).thenReturn(true, false);
        service.refresh();
        verify(market).kospi();
        verify(cache, never()).save(any());
    }

    @Test
    void staleRecentValueIsNotReplacedWithLastSuccessDuringTradingHours() {
        var market = mock(KiwoomMarketClient.class);
        var cache = mock(RedisKospiStore.class);
        var heartbeat = mock(RedisCollectorHeartbeat.class);
        var now = Instant.parse("2026-10-12T00:30:00Z");
        var old = new KiwoomMarketClient.KospiIndex(new java.math.BigDecimal("2650"),
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, now.minusSeconds(60));
        when(cache.read(true)).thenReturn(Optional.of(old));
        var service = new KospiIndexService(market, true, cache, heartbeat, Clock.fixed(now, ZoneOffset.UTC));
        assertThat(service.latest()).isEmpty();
        verify(cache, never()).read(false);
        verifyNoInteractions(market);
    }

    @Test
    void onlyCollectorRefreshesAndEmptyOutsideHoursValueUsesExistingSixtySecondRetryGap() {
        var market = mock(KiwoomMarketClient.class);
        var cache = mock(RedisKospiStore.class);
        var heartbeat = mock(RedisCollectorHeartbeat.class);
        when(heartbeat.ownsLease()).thenReturn(true);
        when(cache.read(false)).thenReturn(Optional.empty());
        var clock = mock(Clock.class);
        var now = Instant.parse("2026-10-12T07:00:00Z");
        when(clock.instant()).thenReturn(now);
        var service = new KospiIndexService(market, true, cache, heartbeat, clock);
        when(market.kospi()).thenThrow(new IllegalStateException("unavailable"));
        service.scheduledRefresh();
        service.scheduledRefresh();
        verify(market).kospi();
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        service.scheduledRefresh();
        verify(market, times(2)).kospi();
        verify(cache, never()).save(any());
    }
}
