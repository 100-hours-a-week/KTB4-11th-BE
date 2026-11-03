package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.user.entity.User;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CompetitionCancellationStateTests {
    private Competition competition() {
        return new Competition(new User("취소 사용자"), "취소 대결", Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), 10000000, 1);
    }

    @Test void waitingAndRunningCompetitionsCanBeCancelledRepeatedly() {
        for (boolean running : new boolean[] {false, true}) {
            var competition = competition();
            if (running) competition.start(Instant.parse("2026-10-01T00:00:00Z"));
            competition.cancel();
            competition.cancel();
            assertThat(competition.getState()).isEqualTo(Competition.State.CANCELLED);
            assertThat(competition.getWinner()).isNull();
            assertThat(competition.getCompletedAt()).isNull();
        }
    }

    @Test void completedCompetitionCannotBeCancelled() {
        var competition = competition();
        competition.start(Instant.parse("2026-10-01T00:00:00Z"));
        competition.complete(Competition.Winner.USER, competition.tradingEndsAt());
        assertThatThrownBy(competition::cancel).isInstanceOf(IllegalStateException.class);
        assertThat(competition.getState()).isEqualTo(Competition.State.COMPLETED);
    }
}
