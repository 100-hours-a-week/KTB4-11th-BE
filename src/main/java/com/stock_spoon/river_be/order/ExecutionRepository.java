package com.stock_spoon.river_be.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRepository extends JpaRepository<Execution, Long> {
    // 주문 생성시각이나 매도 체결을 제외하고, 종목별 마지막 매수 체결시각을 한 번에 읽는다.
    @org.springframework.data.jpa.repository.Query("""
            select e.order.stockCode as stockCode, max(e.createdAt) as lastPurchasedAt
            from Execution e where e.order.account.id = :accountId
            and e.order.side = com.stock_spoon.river_be.order.Order.Side.BUY
            and e.order.stockCode in :stockCodes group by e.order.stockCode
            """)
    java.util.List<LastBuyTime> findLastBuyTimes(
            @org.springframework.data.repository.query.Param("accountId") long accountId,
            @org.springframework.data.repository.query.Param("stockCodes") java.util.List<String> stockCodes);

    @org.springframework.data.jpa.repository.Query("select e from Execution e where e.order.id in :orderIds")
    java.util.List<Execution> findForOrders(
            @org.springframework.data.repository.query.Param("orderIds") java.util.List<Long> orderIds);

    @org.springframework.data.jpa.repository.Query("""
            select e.id as id, e.order.side as side, e.quantity as quantity, e.createdAt as createdAt
            from Execution e where e.order.account.id = :accountId and e.order.stockCode = :stockCode
            and (e.createdAt < :cutoff or (e.createdAt = :cutoff and e.id <= :lastId))
            order by e.createdAt, e.id
            """)
    java.util.List<HoldingTrade> findHoldingTrades(
            @org.springframework.data.repository.query.Param("accountId") long accountId,
            @org.springframework.data.repository.query.Param("stockCode") String stockCode,
            @org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff,
            @org.springframework.data.repository.query.Param("lastId") long lastId);

    interface HoldingTrade {
        Long getId();
        Order.Side getSide();
        long getQuantity();
        java.time.Instant getCreatedAt();
    }

    interface LastBuyTime {
        String getStockCode();
        java.time.Instant getLastPurchasedAt();
    }

    @org.springframework.data.jpa.repository.Query("select e from Execution e where e.order.id = :orderId order by e.id")
    java.util.List<Execution> findForOrder(@org.springframework.data.repository.query.Param("orderId") long orderId);
}
