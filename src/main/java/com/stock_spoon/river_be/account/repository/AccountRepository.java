package com.stock_spoon.river_be.account.repository;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import com.stock_spoon.river_be.account.entity.Account;

public interface AccountRepository extends JpaRepository<Account, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :accountId")
    Optional<Account> findLockedById(@org.springframework.data.repository.query.Param("accountId") Long accountId);

    boolean existsByUserId(Long userId);

    boolean existsByUserIdAndNameIgnoreCaseAndActiveTrue(Long userId, String name);

    boolean existsByUserIdAndNameIgnoreCaseAndActiveTrueAndIdNot(
            Long userId, String name, Long accountId);

    Optional<Account> findByIdAndUserIdAndActiveTrue(Long accountId, Long userId);

    List<Account> findAllByUserIdAndActiveTrueOrderByCreatedAtAscIdAsc(Long userId);

    @Query("select a from Account a join fetch a.user where a.active = true and a.aiManaged = true order by a.user.id, a.createdAt, a.id")
    List<Account> findActiveAiManaged();
}
