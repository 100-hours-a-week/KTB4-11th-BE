package com.stock_spoon.river_be.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @Query("select o from Order o where o.id = :orderId and o.account.id = :accountId")
    Optional<Order> findByIdAndAccountId(@Param("orderId") Long orderId, @Param("accountId") Long accountId);

    @Query("select coalesce(sum(o.reservedCash), 0) from Order o where o.account.id = :accountId and o.status = :status")
    long reservedCash(@Param("accountId") long accountId, @Param("status") Order.Status status);

    @Query("select coalesce(sum(o.quantity), 0) from Order o where o.account.id = :accountId and o.stockCode = :stockCode and o.side = :side and o.status = :status")
    long pendingSellQuantity(@Param("accountId") long accountId, @Param("stockCode") String stockCode,
            @Param("side") Order.Side side, @Param("status") Order.Status status);

    @Query("select o from Order o where o.account.id in :accountIds and o.status = :status order by o.account.id, o.id")
    List<Order> findAllByAccountIdsAndStatus(@Param("accountIds") List<Long> accountIds,
            @Param("status") Order.Status status);
}
