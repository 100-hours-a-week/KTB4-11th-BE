package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient.KospiIndex;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.time.Clock;
import com.stock_spoon.river_be.market.kiwoom.RedisCollectorHeartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class KospiIndexService {
    private static final Logger log = LoggerFactory.getLogger(KospiIndexService.class);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final KiwoomMarketClient client;
    private final boolean refreshEnabled;
    private final RedisKospiStore store;
    private final RedisCollectorHeartbeat heartbeat;
    private final Clock clock;
    private volatile Instant lastAttempt = Instant.EPOCH;

    @org.springframework.beans.factory.annotation.Autowired
    public KospiIndexService(KiwoomMarketClient client,
            @Value("${market.kospi.refresh-enabled:true}") boolean refreshEnabled,
            RedisKospiStore store, RedisCollectorHeartbeat heartbeat) {
        this(client, refreshEnabled, store, heartbeat, Clock.systemUTC());
    }

    KospiIndexService(KiwoomMarketClient client, boolean refreshEnabled,
            RedisKospiStore store, RedisCollectorHeartbeat heartbeat, Clock clock) {
        this.client = client;
        this.refreshEnabled = refreshEnabled;
        this.store = store;
        this.heartbeat = heartbeat;
        this.clock = clock;
    }

    public Optional<KospiIndex> latest() {
        Instant now = clock.instant();
        boolean recent = isRefreshWindow(now.atZone(KOREA));
        return store.read(recent).filter(value -> !value.fetchedAt().isAfter(now)
                && now.isBefore(value.fetchedAt().plusSeconds(recent ? 60 : 604800)));
    }

    @Scheduled(fixedDelayString = "${market.kospi.refresh-ms:10000}")
    void scheduledRefresh() {
        if (!refreshEnabled || !heartbeat.ownsLease()) {
            return;
        }
        ZonedDateTime now = clock.instant().atZone(KOREA);
        boolean needsInitialValue = store.read(false).isEmpty()
                && !now.toInstant().isBefore(lastAttempt.plusSeconds(60));
        if (isRefreshWindow(now) || needsInitialValue) {
            lastAttempt = now.toInstant();
            refresh();
        }
    }

    void refresh() {
        try {
            if (!heartbeat.ownsLease()) return;
            var value = client.kospi();
            if (!heartbeat.ownsLease()) return;
            store.save(value);
            log.debug("event=kospi_refresh_completed");
        } catch (RuntimeException error) {
            // Keep the last successful value and never log provider response or credentials.
            log.warn("event=kospi_refresh_failed causeType={}", error.getClass().getSimpleName());
        }
    }

    static boolean isRefreshWindow(ZonedDateTime now) {
        DayOfWeek day = now.getDayOfWeek();
        LocalTime time = now.toLocalTime();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
                && !time.isBefore(LocalTime.of(9, 0))
                && time.isBefore(LocalTime.of(15, 40));
    }
}
