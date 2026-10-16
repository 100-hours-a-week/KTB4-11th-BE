package com.stock_spoon.river_be.competition.repository;

import com.stock_spoon.river_be.competition.entity.CompetitionJudgmentSummary;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompetitionJudgmentSummaryRepository extends JpaRepository<CompetitionJudgmentSummary, Long> {
    List<CompetitionJudgmentSummary> findAllByCompetitionIdOrderByIdAsc(Long competitionId);
}
