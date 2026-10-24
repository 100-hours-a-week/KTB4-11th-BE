package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.dto.CompetitionTradeHistoryResponse;
import com.stock_spoon.river_be.competition.dto.CompetitionTradeHistoryCursor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompetitionTradeHistoryService.class);
    private final AccountRepository accounts;
    private final CompetitionHistoryAccess access;
    private final CompetitionTradeHistoryRepository trades;
    private final KiwoomMarketClient market;

    public CompetitionTradeHistoryResponse list(long userId, long accountId, String cursor, Integer limit, String orderSide) {
        // 1. 소유 계좌인지 확인한 뒤 대결 참가 여부와 AI 공개 시점을 검사한다.
        if (accounts.findByIdAndUserIdAndActiveTrue(accountId, userId).isEmpty()) {
            throw new CompetitionException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "계좌를 찾을 수 없습니다.");
        }
        access.check(userId, accountId, true);
        // 외부 입력은 소문자 buy/sell만 허용한다. 생략하면 매수·매도 모두 조회한다.
        if (orderSide != null && !"buy".equals(orderSide) && !"sell".equals(orderSide)) {
            throw new CompetitionException(HttpStatus.BAD_REQUEST, "INVALID_TRADE_HISTORY_ORDER_SIDE",
                    "매매 유형은 buy 또는 sell이어야 합니다.");
        }
        if (limit != null && (limit < 1 || limit > 100)) {
            throw new CompetitionException(HttpStatus.BAD_REQUEST, "INVALID_TRADE_HISTORY_LIMIT", "조회 개수는 1부터 100까지 가능합니다.");
        }
        // 검증한 문자열을 Entity 필드와 같은 Enum 타입으로 바꾸어 쿼리에 전달한다.
        var side = orderSide == null ? null
                : com.stock_spoon.river_be.order.entity.Order.Side.valueOf(orderSide.toUpperCase(Locale.ROOT));
        var position = CompetitionTradeHistoryCursor.decode(cursor, accountId, orderSide);
        boolean paged = cursor != null || limit != null;
        int pageSize = limit == null ? 10 : limit;
        // 2. 주문별 최신 체결 시각과 ID로 순서를 정해 조회한다. 전체 조회에는 제한을 걸지 않는다.
        var fetched = trades.findOrderPositions(accountId, side,
                position == null ? null : position.getExecutedAt(),
                position == null ? null : position.getOrderId(),
                paged ? PageRequest.of(0, pageSize + 1) : Pageable.unpaged());
        boolean hasNext = paged && fetched.size() > pageSize;
        var selected = hasNext ? fetched.subList(0, pageSize) : fetched;
        String nextCursor = null;
        for (var row : selected) {
            if (row.getExecutedAt() == null) throw invalidOrderData();
        }
        if (hasNext) {
            var last = selected.getLast();
            nextCursor = new CompetitionTradeHistoryCursor(last.getExecutedAt(), last.getOrderId(), accountId, orderSide).encode();
        }
        var pagination = paged ? new CompetitionTradeHistoryResponse.Pagination(pageSize, hasNext, nextCursor) : null;
        if (selected.isEmpty()) return new CompetitionTradeHistoryResponse(accountId, List.of(), pagination);

        // 3. 응답할 주문의 체결만 모두 읽는다. LinkedHashMap에 먼저 주문 정렬 순서를 넣는다.
        var byOrder = new LinkedHashMap<Long, List<TradeRow>>();
        var orderIds = selected.stream().map(p -> p.getOrderId()).toList();
        orderIds.forEach(id -> byOrder.put(id, new ArrayList<>()));
        for (var row : trades.findExecutedTrades(accountId, orderIds)) {
            if (row.getExecutedAt() == null) {
                throw invalidOrderData();
            }
            byOrder.get(row.getOrderId()).add(row);
        }
        // 3. 합산과 응답 생성은 대결 서비스에서 독립적으로 처리한다.
        var names = new HashMap<String, String>();
        var items = byOrder.values().stream().map(fills -> {
            if (fills.isEmpty()) throw invalidOrderData();
            return summarize(fills, names);
        }).toList();
        return new CompetitionTradeHistoryResponse(accountId, items, pagination);
    }

    private CompetitionException invalidOrderData() {
        log.error("event=competition_trade_history_failed stage=execution_data_validation code=INVALID_ORDER_DATA");
        return new CompetitionException(HttpStatus.INTERNAL_SERVER_ERROR, "INVALID_ORDER_DATA", "주문 체결 데이터를 확인해주세요.");
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
                log.warn("event=competition_trade_history_failed stage=stock_details_lookup orderId={} stockCode={} code=ORDER_DATA_UNAVAILABLE causeType={}",
                        first.getOrderId(), code, error.getClass().getSimpleName());
                throw new CompetitionException(HttpStatus.SERVICE_UNAVAILABLE, "ORDER_DATA_UNAVAILABLE", "주문 종목 정보를 조회할 수 없습니다.");
            }
        });
        return new CompetitionTradeHistoryResponse.Item(first.getOrderId(), first.getStockCode(), name,
                first.getSide().toLowerCase(Locale.ROOT), first.getExecutedAt().atOffset(ZoneOffset.ofHours(9)),
                quantity, amount, realized, rate);
    }
}
