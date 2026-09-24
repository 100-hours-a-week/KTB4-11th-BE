package com.stock_spoon.river_be.account.repository;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.stock_spoon.river_be.account.entity.Account;

public interface AccountRepository extends JpaRepository<Account, Long> {
    boolean existsByUserId(Long userId);

    boolean existsByUserIdAndNameIgnoreCaseAndActiveTrue(Long userId, String name);

    boolean existsByUserIdAndNameIgnoreCaseAndActiveTrueAndIdNot(
            Long userId, String name, Long accountId);

    Optional<Account> findByIdAndUserIdAndActiveTrue(Long accountId, Long userId);

    List<Account> findAllByUserIdAndActiveTrueOrderByCreatedAtAscIdAsc(Long userId);
}
