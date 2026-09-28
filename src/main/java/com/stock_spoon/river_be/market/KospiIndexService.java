package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient.KospiIndex;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AtomicReference<KospiIndex> latest = new AtomicReference<>();
    private volatile Instant lastAttempt = Instant.EPOCH;

    public KospiIndexService(KiwoomMarketClient client,
            @Value("${market.kospi.refresh-enabled:true}") boolean refreshEnabled) {
        this.client = client;
        this.refreshEnabled = refreshEnabled;
    }

    public Optional<KospiIndex> latest() {
        return Optional.ofNullable(latest.get());
    }

    @Scheduled(fixedDelayString = "${market.kospi.refresh-ms:10000}")
    void scheduledRefresh() {
        if (!refreshEnabled) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(KOREA);
        boolean needsInitialValue = latest.get() == null
                && !now.toInstant().isBefore(lastAttempt.plusSeconds(60));
        if (isRefreshWindow(now) || needsInitialValue) {
            lastAttempt = now.toInstant();
            refresh();
        }
    }

    void refresh() {
        try {
            latest.set(client.kospi());
        } catch (RuntimeException error) {
            // Keep the last successful value and never log provider response or credentials.
            log.warn("키움 코스피 지수 갱신에 실패했습니다.");
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