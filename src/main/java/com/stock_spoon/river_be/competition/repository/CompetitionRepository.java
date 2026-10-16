package com.stock_spoon.river_be.competition.repository;

import com.stock_spoon.river_be.competition.entity.Competition;
import org.springframework.data.jpa.repository.JpaRepository;

// save(), findById() 등 기본 저장·조회 기능은 Spring Data JPA가 구현한다.
public interface CompetitionRepository extends JpaRepository<Competition, Long> {
    // 소유자를 함께 조건으로 걸어 타인의 대결을 상세 조회하지 못하게 한다.
    java.util.Optional<Competition> findByIdAndUserId(Long id, Long userId);

    // 완료 대결도 포함해 서버 중단 중 누락된 과거 일별 성적을 복구한다.
    @org.springframework.data.jpa.repository.Query("select c.id from Competition c "
            + "where c.state in (IN_PROGRESS, COMPLETED) and c.startedAt is not null "
            + "and c.startDate <= :today order by c.id")
    java.util.List<Long> findDailyPerformanceCandidates(java.time.LocalDate today);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from Competition c where c.id = :id")
    java.util.Optional<Competition> findLockedById(Long id);

    // 일요일 종료 대결도 금요일 후보에 포함하고, 정확한 종료 시각은 Entity에서 판단한다.
    @org.springframework.data.jpa.repository.Query("select c.id from Competition c where c.state = IN_PROGRESS "
            + "and c.endDate <= :latestEndDate order by c.id")
    java.util.List<Long> findCompletionCandidates(java.time.LocalDate latestEndDate);
    java.util.List<Competition> findAllByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    // 여러 BE 인스턴스가 동시에 실행해도 같은 대결을 중복 시작하지 않도록 잠근다.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from Competition c where c.state = READY "
            + "and c.startDate <= :today and c.endDate >= :today order by c.id")
    java.util.List<Competition> findWaitingToStart(java.time.LocalDate today);
}
