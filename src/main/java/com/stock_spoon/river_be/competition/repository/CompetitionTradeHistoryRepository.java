package com.stock_spoon.river_be.competition.repository;

import com.stock_spoon.river_be.competition.entity.CompetitionParticipant;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

// 기존 주문 조회 Repository와 분리하여 대결 화면에서 필요한 값만 읽는다.
public interface CompetitionTradeHistoryRepository extends Repository<CompetitionParticipant, Long> {
    // 체결 행이 아닌 주문을 먼저 선택해야 한 주문의 체결 합산이 페이지 경계에서 잘리지 않는다.
    @Query("""
            select o.id as orderId, max(e.createdAt) as executedAt
            from Order o left join Execution e on e.order = o
            where o.account.id = :accountId and o.status = 'EXECUTED'
            group by o.id
            having (:cursorTime is null or max(e.createdAt) < :cursorTime
                    or (max(e.createdAt) = :cursorTime and o.id < :cursorId))
            order by max(e.createdAt) desc, o.id desc
            """)
    List<OrderPosition> findOrderPositions(
            @org.springframework.data.repository.query.Param("accountId") long accountId,
            @org.springframework.data.repository.query.Param("cursorTime") Instant cursorTime,
            @org.springframework.data.repository.query.Param("cursorId") Long cursorId,
            org.springframework.data.domain.Pageable pageable);

    @Query("""
            select o.id as orderId, o.stockCode as stockCode, cast(o.side as string) as side,
                   e.price as price, e.quantity as quantity, e.realizedPnl as realizedPnl,
                   e.realizedReturnPercent as realizedReturnPercent, e.createdAt as executedAt
            from Order o left join Execution e on e.order = o
            where o.account.id = :accountId and o.status = 'EXECUTED' and o.id in :orderIds
            order by e.createdAt desc, o.id desc, e.id desc
            """)
    List<TradeRow> findExecutedTrades(
            @org.springframework.data.repository.query.Param("accountId") long accountId,
            @org.springframework.data.repository.query.Param("orderIds") java.util.Collection<Long> orderIds);

    interface OrderPosition {
        Long getOrderId();
        Instant getExecutedAt();
    }

    interface TradeRow {
        Long getOrderId();
        String getStockCode();
        String getSide();
        Long getPrice();
        Long getQuantity();
        BigDecimal getRealizedPnl();
        BigDecimal getRealizedReturnPercent();
        Instant getExecutedAt();
    }
}
