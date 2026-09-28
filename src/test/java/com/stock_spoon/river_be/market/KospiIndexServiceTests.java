package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient.KospiIndex;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KospiIndexServiceTests {
    @Test
    void keepsLastSuccessfulIndexWhenRefreshFails() {
        var client = mock(KiwoomMarketClient.class);
        var service = new KospiIndexService(client, false);
        var first = new KospiIndex(new BigDecimal("2817.42"), new BigDecimal("-12.50"),
                new BigDecimal("-0.44"), Instant.parse("2026-09-26T06:30:00Z"));
        when(client.kospi()).thenReturn(first).thenThrow(new IllegalStateException("provider-secret"));

        service.refresh();
        service.refresh();

        assertThat(service.latest()).contains(first);
        verify(client, times(2)).kospi();
    }

    @Test
    void refreshWindowUsesKoreanWeekdayTradingHours() {
        var korea = ZoneId.of("Asia/Seoul");
        assertThat(KospiIndexService.isRefreshWindow(
                ZonedDateTime.of(2026, 9, 28, 9, 0, 0, 0, korea))).isTrue();
        assertThat(KospiIndexService.isRefreshWindow(
                ZonedDateTime.of(2026, 9, 28, 15, 39, 59, 0, korea))).isTrue();
        assertThat(KospiIndexService.isRefreshWindow(
                ZonedDateTime.of(2026, 9, 28, 15, 40, 0, 0, korea))).isFalse();
        assertThat(KospiIndexService.isRefreshWindow(
                ZonedDateTime.of(2026, 9, 27, 10, 0, 0, 0, korea))).isFalse();
    }
}
