package com.stock_spoon.river_be.competition.repository;

import com.stock_spoon.river_be.competition.entity.CompetitionParticipant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompetitionParticipantRepository extends JpaRepository<CompetitionParticipant, Long> {
    // 참가자와 계좌를 함께 읽어 각 참가자의 account_id와 AI 운용 여부를 확인한다.
    @EntityGraph(attributePaths = "account")
    List<CompetitionParticipant> findAllByCompetitionIdOrderByIdAsc(Long competitionId);

    @EntityGraph(attributePaths = "competition")
    Optional<CompetitionParticipant> findByAccountId(Long accountId);

    boolean existsByAccountId(Long accountId);

    @org.springframework.data.jpa.repository.Query("select p.account.id from CompetitionParticipant p where p.account.id in :accountIds")
    java.util.Set<Long> findCompetitionAccountIds(java.util.Collection<Long> accountIds);
}
