package com.stock_spoon.river_be.competition.repository;

import com.stock_spoon.river_be.competition.entity.CompetitionDailyPerformance;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompetitionDailyPerformanceRepository extends JpaRepository<CompetitionDailyPerformance, Long> {
    List<CompetitionDailyPerformance> findAllByParticipantIdOrderByRecordDateAsc(Long participantId);

    List<CompetitionDailyPerformance> findAllByParticipantCompetitionId(Long competitionId);

    Optional<CompetitionDailyPerformance> findByParticipantIdAndRecordDate(Long participantId, LocalDate recordDate);
}
