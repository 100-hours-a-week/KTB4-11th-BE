package com.stock_spoon.river_be.competition.repository;

import com.stock_spoon.river_be.competition.entity.CompetitionParticipant;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

// 기존 주문 조회 Repository와 분리하여 대결 화면에서 필요한 값만 읽는다.
public interface CompetitionTradeHistoryRepository extends Repository<CompetitionParticipant, Long> {
    @Query("""
            select o.id as orderId, o.stockCode as stockCode, cast(o.side as string) as side,
                   e.price as price, e.quantity as quantity, e.realizedPnl as realizedPnl,
                   e.realizedReturnPercent as realizedReturnPercent, e.createdAt as executedAt
            from Order o left join Execution e on e.order = o
            where o.account.id = :accountId and o.status = 'EXECUTED'
            order by e.createdAt desc, o.id desc, e.id desc
            """)
    List<TradeRow> findExecutedTrades(long accountId);

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
