package com.stock_spoon.river_be.account.repository;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import com.stock_spoon.river_be.account.entity.Account;

public interface AccountRepository extends JpaRepository<Account, Long> {
    @Query("select p.competition from CompetitionParticipant p where p.account.id = :accountId")
    Optional<com.stock_spoon.river_be.competition.entity.Competition> findCompetitionByAccountId(Long accountId);

    default boolean hasNonTradingCompetition(Long accountId, java.time.ZonedDateTime now) {
        return findCompetitionByAccountId(accountId).map(c -> !c.isTradingAt(now)).orElse(false);
    }
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :accountId")
    Optional<Account> findLockedById(@org.springframework.data.repository.query.Param("accountId") Long accountId);

    boolean existsByUserId(Long userId);

    // 비활성 계좌도 DB의 이름 유일 제약에 포함되므로 이름을 재사용하지 않는다.
    boolean existsByUserIdAndNameIgnoreCase(Long userId, String name);
    boolean existsByUserIdAndNameIgnoreCaseAndIdNot(Long userId, String name, Long accountId);

    boolean existsByUserIdAndNameIgnoreCaseAndActiveTrue(Long userId, String name);

    boolean existsByUserIdAndNameIgnoreCaseAndActiveTrueAndIdNot(
            Long userId, String name, Long accountId);

    Optional<Account> findByIdAndUserIdAndActiveTrue(Long accountId, Long userId);

    // [계좌 목록 수정] 대결 AI 계좌도 포함해 본인의 모든 활성 계좌를 생성 시각·ID 순으로 반환한다.
    @Query("select a from Account a where a.user.id = :userId and a.active = true "
            + "order by a.createdAt, a.id")
    List<Account> findAllByUserIdAndActiveTrueOrderByCreatedAtAscIdAsc(Long userId);

    // 일반 v1 AI 계좌는 유지한다. 대결 계좌는 진행 중이고 기간 안인 경우에만 운용 대상으로 공개한다.
    @Query("select a from Account a join fetch a.user where a.active = true and a.aiManaged = true "
            + "and (not exists (select p.id from CompetitionParticipant p where p.account = a) "
            + "or exists (select p.id from CompetitionParticipant p where p.account = a "
            + "and p.competition.state = IN_PROGRESS "
            + "and p.competition.startDate <= :today and p.competition.endDate >= :today)) "
            + "order by a.user.id, a.createdAt, a.id")
    List<Account> findActiveAiManagedOn(java.time.LocalDate today);

    default List<Account> findActiveAiManaged() {
        return findActiveAiManagedOn(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));
    }
}
