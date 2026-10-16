package com.stock_spoon.river_be.competition.repository;

import com.stock_spoon.river_be.competition.entity.Competition;
import org.springframework.data.jpa.repository.JpaRepository;

// save(), findById() 등 기본 저장·조회 기능은 Spring Data JPA가 구현한다.
public interface CompetitionRepository extends JpaRepository<Competition, Long> {
    // 여러 BE 인스턴스가 동시에 실행해도 같은 대결을 중복 시작하지 않도록 잠근다.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from Competition c where c.state = READY "
            + "and c.startDate <= :today and c.endDate >= :today order by c.id")
    java.util.List<Competition> findWaitingToStart(java.time.LocalDate today);
}
