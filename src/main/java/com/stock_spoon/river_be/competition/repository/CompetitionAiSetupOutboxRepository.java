package com.stock_spoon.river_be.competition.repository;

import com.stock_spoon.river_be.competition.entity.CompetitionAiSetupOutbox;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

// 대기 요청의 저장과 기본 조회는 Spring Data JPA가 구현한다.
public interface CompetitionAiSetupOutboxRepository extends JpaRepository<CompetitionAiSetupOutbox, Long> {
    java.util.Optional<CompetitionAiSetupOutbox> findByCompetitionId(Long competitionId);
    boolean existsByCompetitionIdAndStatus(Long competitionId, CompetitionAiSetupOutbox.Status status);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from CompetitionAiSetupOutbox o where o.id = :id")
    java.util.Optional<CompetitionAiSetupOutbox> findLockedById(Long id);

    @org.springframework.data.jpa.repository.Query("select max(o.id) from CompetitionAiSetupOutbox o where o.status = PENDING")
    Long findPendingUpperBound();

    // 이번 실행 시작 시 존재한 후보만 ID 순으로 순회해 실패 행이 뒤쪽 요청을 막지 않게 한다.
    @org.springframework.data.jpa.repository.Query("select o.id from CompetitionAiSetupOutbox o "
            + "where o.status = PENDING and o.id > :afterId and o.id <= :upperId order by o.id")
    List<Long> findPendingIdsBetween(long afterId, long upperId, Pageable pageable);

}
