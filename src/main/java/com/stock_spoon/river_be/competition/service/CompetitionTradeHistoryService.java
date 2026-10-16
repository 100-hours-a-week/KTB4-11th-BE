package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.dto.CompetitionTradeHistoryResponse;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.exception.CompetitionException;
import com.stock_spoon.river_be.competition.repository.CompetitionTradeHistoryRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionTradeHistoryRepository.TradeRow;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CompetitionTradeHistoryService {
    private final AccountRepository accounts;
    private final CompetitionHistoryAccess access;
    private final CompetitionTradeHistoryRepository trades;
    private final KiwoomMarketClient market;

    public CompetitionTradeHistoryResponse list(long userId, long accountId) {
        // 1. 소유 계좌인지 확인한 뒤 대결 참가 여부와 AI 공개 시점을 검사한다.
        if (accounts.findByIdAndUserIdAndActiveTrue(accountId, userId).isEmpty()) {
            throw new CompetitionException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "계좌를 찾을 수 없습니다.");
        }
        access.check(userId, accountId, true);
        // 2. 최근 체결부터 읽어 주문별로 묶는다. LinkedHashMap으로 최근 주문 순서를 유지한다.
        var byOrder = new LinkedHashMap<Long, List<TradeRow>>();
        for (var row : trades.findExecutedTrades(accountId)) {
            if (row.getExecutedAt() == null) {
                throw new CompetitionException(HttpStatus.INTERNAL_SERVER_ERROR, "INVALID_ORDER_DATA", "주문 체결 데이터를 확인해주세요.");
            }
            byOrder.computeIfAbsent(row.getOrderId(), key -> new ArrayList<>()).add(row);
        }
        // 3. 합산과 응답 생성은 대결 서비스에서 독립적으로 처리한다.
        var names = new HashMap<String, String>();
        var items = byOrder.values().stream().map(fills -> summarize(fills, names)).toList();
        return new CompetitionTradeHistoryResponse(accountId, items);
    }

    private CompetitionTradeHistoryResponse.Item summarize(List<TradeRow> fills, Map<String, String> names) {
        var first = fills.getFirst();
        long quantity = 0;
        long amount = 0;
        BigDecimal pnl = BigDecimal.ZERO;
        boolean completePnl = true;
        for (var fill : fills) {
            quantity = Math.addExact(quantity, fill.getQuantity());
            amount = Math.addExact(amount, Math.multiplyExact(fill.getPrice(), fill.getQuantity()));
            if (fill.getRealizedPnl() == null) completePnl = false;
            else pnl = pnl.add(fill.getRealizedPnl());
        }
        boolean sell = "SELL".equals(first.getSide());
        BigDecimal realized = sell && completePnl ? pnl : null;
        BigDecimal rate = null;
        if (sell && fills.size() == 1) rate = first.getRealizedReturnPercent();
        else if (realized != null) {
            // 여러 호가에서 전량 체결된 주문은 합산 취득원가를 기준으로 수익률을 계산한다.
            var cost = BigDecimal.valueOf(amount).subtract(realized);
            if (cost.signum() > 0) rate = realized.multiply(BigDecimal.valueOf(100))
                    .divide(cost, 4, RoundingMode.HALF_UP);
        }
        String name = names.computeIfAbsent(first.getStockCode(), code -> {
            try {
                return market.stockDetails(code).stockName();
            } catch (IllegalStateException error) {
                throw new CompetitionException(HttpStatus.SERVICE_UNAVAILABLE, "ORDER_DATA_UNAVAILABLE", "주문 종목 정보를 조회할 수 없습니다.");
            }
        });
        return new CompetitionTradeHistoryResponse.Item(first.getOrderId(), first.getStockCode(), name,
                first.getSide().toLowerCase(Locale.ROOT), first.getExecutedAt().atOffset(ZoneOffset.ofHours(9)),
                quantity, amount, realized, rate);
    }
}
