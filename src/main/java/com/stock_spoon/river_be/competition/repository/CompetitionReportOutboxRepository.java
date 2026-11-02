package com.stock_spoon.river_be.competition.repository;

import com.stock_spoon.river_be.competition.entity.CompetitionReportOutbox;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

// 대기 요청의 저장과 기본 조회는 Spring Data JPA가 구현한다.
public interface CompetitionReportOutboxRepository extends JpaRepository<CompetitionReportOutbox, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from CompetitionReportOutbox o where o.id = :id")
    java.util.Optional<CompetitionReportOutbox> findLockedById(Long id);

    @org.springframework.data.jpa.repository.Query("select max(o.id) from CompetitionReportOutbox o where o.status = PENDING")
    Long findPendingUpperBound();

    // 이번 실행 시작 시 존재한 후보만 ID 순으로 순회해 실패 행이 뒤쪽 요청을 막지 않게 한다.
    @org.springframework.data.jpa.repository.Query("select o.id from CompetitionReportOutbox o "
            + "where o.status = PENDING and o.id > :afterId and o.id <= :upperId order by o.id")
    List<Long> findPendingIdsBetween(long afterId, long upperId, Pageable pageable);

    // 보고서가 없거나 과거 reason_id가 없는 체결도 null로 읽어 누락 건수를 확인한다.
    @org.springframework.data.jpa.repository.Query("select r.reasonId from Order o left join o.report r "
            + "where o.account.id = :accountId and o.source = AI and o.status = EXECUTED order by o.id")
    List<String> findExecutedAiReasonIds(Long accountId);

    // 한 번에 처리할 개수만 조회한다. List 반환으로 전체 개수 COUNT 쿼리를 실행하지 않는다.
    List<CompetitionReportOutbox> findByStatusOrderByIdAsc(CompetitionReportOutbox.Status status, Pageable pageable);
}
