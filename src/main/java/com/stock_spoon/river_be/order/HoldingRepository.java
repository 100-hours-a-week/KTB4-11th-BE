package com.stock_spoon.river_be.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface HoldingRepository extends JpaRepository<Holding, Long> {
    @Query("select h from Holding h where h.account.id = :accountId and h.stockCode = :stockCode")
    Optional<Holding> findByAccountIdAndStockCode(@Param("accountId") Long accountId, @Param("stockCode") String stockCode);
}
