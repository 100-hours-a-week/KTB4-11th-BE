package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.competition.service.CompetitionStartScheduler;
import com.stock_spoon.river_be.competition.service.CompetitionTradingHours;
import com.stock_spoon.river_be.user.entity.User;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CompetitionStartSchedulerBoundaryTests {
    @ParameterizedTest
    @ValueSource(strings = {"2026-11-13T15:30:00+09:00[Asia/Seoul]", "2026-11-16T09:00:00+09:00[Asia/Seoul]"})
    void doesNotUseTheOldClockAfterWaitingForDatabaseLocks(String afterWait) {
        var before = ZonedDateTime.parse("2026-11-13T15:29:59+09:00[Asia/Seoul]");
        var c = new Competition(new User("경계 검사"), "장 마감", Competition.Type.AI_VS_AI,
                before.toLocalDate().minusDays(3), before.toLocalDate().plusDays(2), 3_000_000, 2);
        var hours = spy(new CompetitionTradingHours());
        doReturn(before, ZonedDateTime.parse(afterWait)).when(hours).now();
        var competitions = mock(CompetitionRepository.class);
        when(competitions.findWaitingToStart(before.toLocalDate())).thenReturn(List.of(c));
        new CompetitionStartScheduler(competitions, hours).startWaitingCompetitions();
        assertThat(c.getState()).isEqualTo(Competition.State.READY);
        assertThat(c.getStartedAt()).isNull();
    }
}
