package com.stock_spoon.river_be.competition.repository;

import com.stock_spoon.river_be.competition.entity.Competition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;

// save(), findById() 등 기본 저장·조회 기능은 Spring Data JPA가 구현한다.
public interface CompetitionRepository extends JpaRepository<Competition, Long> {
    // [회원 탈퇴 추가] 소유 대결 전체를 ID 순으로 잠근 뒤 계좌를 잠가, 기존 대결 정산의 잠금 순서를 유지한다.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Competition c where c.user.id = :userId order by c.id")
    List<Competition> findAllLockedByUserId(long userId);
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

    // 첫 요청은 커서가 null이다. 다음 요청부터는 마지막 항목보다 오래된 대결만 조회한다.
    // 생성 시각이 같으면 ID로 순서를 구분해 중복·누락을 막는다.
    // Pageable은 항상 첫 페이지로 전달하고, limit+1개까지 가져오는 용도로만 사용한다.
    @Query("""
            select c from Competition c
            where c.user.id = :userId
              and (:state is null or c.state = :state)
              and (:cursorCreatedAt is null
                   or c.createdAt < :cursorCreatedAt
                   or (c.createdAt = :cursorCreatedAt and c.id < :cursorId))
            order by c.createdAt desc, c.id desc
            """)
    List<Competition> findCursorPage(
            @Param("userId") Long userId,
            @Param("state") Competition.State state,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    // 현재 페이지·조회 필터와 별개로 사용자의 전체 진행 중 대결 수를 계산한다.
    long countByUserIdAndState(Long userId, Competition.State state);

    // 여러 BE 인스턴스가 동시에 실행해도 같은 대결을 중복 시작하지 않도록 잠근다.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from Competition c where c.state = READY "
            + "and c.startDate <= :today and c.endDate >= :today order by c.id")
    java.util.List<Competition> findWaitingToStart(java.time.LocalDate today);
}
