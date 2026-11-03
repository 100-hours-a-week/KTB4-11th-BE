package com.stock_spoon.river_be.competition.repository;

import com.stock_spoon.river_be.competition.entity.CompetitionAgent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompetitionAgentRepository extends JpaRepository<CompetitionAgent, Long> {
    List<CompetitionAgent> findAllByParticipantCompetitionIdOrderByPositionAsc(Long competitionId);
}
