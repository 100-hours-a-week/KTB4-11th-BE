package com.stock_spoon.river_be.competition.repository;

import com.stock_spoon.river_be.competition.entity.CompetitionReportOutbox;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

// 대기 요청의 저장과 기본 조회는 Spring Data JPA가 구현한다.
public interface CompetitionReportOutboxRepository extends JpaRepository<CompetitionReportOutbox, Long> {
    // 한 번에 처리할 개수만 조회한다. List 반환으로 전체 개수 COUNT 쿼리를 실행하지 않는다.
    List<CompetitionReportOutbox> findByStatusOrderByIdAsc(CompetitionReportOutbox.Status status, Pageable pageable);
}
