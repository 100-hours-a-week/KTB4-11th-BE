package com.stock_spoon.river_be.competition.service;

import java.time.ZonedDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class CompetitionTradingHoursTests {
    @ParameterizedTest
    @CsvSource({
        "2026-10-16T08:59:59+09:00,false",
        "2026-10-16T09:00:00+09:00,true",
        "2026-10-16T15:29:59+09:00,true",
        "2026-10-16T15:30:00+09:00,false",
        "2026-10-17T10:00:00+09:00,false",
        "2026-10-18T10:00:00+09:00,false",
        "2026-10-19T09:00:00+09:00,true"
    })
    void classifiesWeekdayMarketBoundaries(String instant, boolean expected) {
        var hours = new CompetitionTradingHours();
        assertThat(hours.isOpen(ZonedDateTime.parse(instant))).isEqualTo(expected);
    }
}
