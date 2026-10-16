package com.stock_spoon.river_be.competition.repository;

import com.stock_spoon.river_be.competition.entity.CompetitionMarketAnalysis;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompetitionMarketAnalysisRepository extends JpaRepository<CompetitionMarketAnalysis, Long> {
    List<CompetitionMarketAnalysis> findAllByCompetitionIdOrderByDateDescIdDesc(Long competitionId);
}
