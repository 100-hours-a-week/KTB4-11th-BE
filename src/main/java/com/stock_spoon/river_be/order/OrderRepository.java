package com.stock_spoon.river_be.order;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;

// Spring Data JPA가 구현을 제공한다. save/findById 등은 JpaRepository에서 상속한다.
// @Query의 Order와 필드명은 Entity 기준이며 JPA가 테이블·컬럼에 맞는 SQL로 변환한다.
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findAllByStatus(Order.Status status);

    @Query("select o from Order o join fetch o.account where o.stockCode = :stockCode "
            + "and o.status = :status and o.type = :type")
    List<Order> findPendingForPrice(@Param("stockCode") String stockCode,
            @Param("status") Order.Status status, @Param("type") Order.Type type);

    @Query("select distinct o.account.id from Order o where o.status = :status order by o.account.id")
    List<Long> findAccountIdsByStatus(@Param("status") Order.Status status);

    @Query("select o from Order o where o.account.id = :accountId and o.status = :status")
    List<Order> findAllByAccountIdAndStatus(@Param("accountId") long accountId, @Param("status") Order.Status status);

    @Query("select distinct o.stockCode from Order o where o.status = :status")
    java.util.Set<String> findStockCodesByStatus(@Param("status") Order.Status status);

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
