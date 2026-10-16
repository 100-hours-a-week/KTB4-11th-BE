package com.stock_spoon.river_be.competition.service;

import java.time.*;
import org.springframework.stereotype.Component;

@Component // 서비스와 시작 대기 처리에서 같은 시간 기준을 사용한다.
public class CompetitionTradingHours {
    private final Clock clock;

    public CompetitionTradingHours() { this(Clock.systemUTC()); }

    // 테스트에서는 고정된 시계를 넣어 주말·장 시작·장 마감 경계를 재현한다.
    CompetitionTradingHours(Clock clock) { this.clock = clock; }

    public ZonedDateTime now() { return clock.instant().atZone(ZoneId.of("Asia/Seoul")); }

    public boolean isOpen(ZonedDateTime now) {
        var day = now.getDayOfWeek();
        var time = now.toLocalTime();
        // 현재 합의한 정책은 평일 09:00 이상, 15:30 미만이다. 공휴일은 추후 반영한다.
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
                && !time.isBefore(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(15, 30));
    }
}
