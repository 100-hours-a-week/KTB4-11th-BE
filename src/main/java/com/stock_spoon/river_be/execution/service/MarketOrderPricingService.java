package com.stock_spoon.river_be.execution.service;

import com.stock_spoon.river_be.market.OrderBook;
import com.stock_spoon.river_be.market.QuoteLevel;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.exception.OrderException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MarketOrderPricingService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MarketOrderPricingService.class);
    public record Estimate(long amount, List<QuoteLevel> fills) {}

    public Estimate estimate(String stockCode, Order.Side side, long quantity, OrderBook book, Instant now) {
        if (quantity <= 0 || side == null) throw new OrderException("주문 수량을 확인하세요.");
        validateBook(stockCode, book, now);
        var levels = side == Order.Side.BUY ? book.asks() : book.bids();
        var priority = Comparator.comparing(QuoteLevel::price);
        if (side == Order.Side.SELL) priority = priority.reversed();
        long remaining = quantity, amount = 0;
        var fills = new ArrayList<QuoteLevel>();
        try {
            for (var level : levels) {
                if (level == null || level.price() == null || level.quantity() < 0
                        || level.price().signum() < 0 || (level.quantity() > 0 && level.price().signum() == 0)) {
                    log.warn("event=market_order_pricing_failed stage=quote_level_validation stockCode={} side={} code=INVALID_QUOTE_LEVEL", stockCode, side);
                    throw unavailable();
                }
                try { level.price().longValueExact(); }
                catch (ArithmeticException e) {
                    log.warn("event=market_order_pricing_failed stage=quote_price_conversion stockCode={} side={} code=PRICE_NOT_EXACT_LONG", stockCode, side);
                    throw unavailable();
                }
            }
            for (var level : levels.stream().sorted(priority).toList()) {
                if (remaining == 0) break;
                long filled = Math.min(remaining, level.quantity());
                if (filled == 0) continue;
                amount = Math.addExact(amount, Math.multiplyExact(level.price().longValueExact(), filled));
                fills.add(new QuoteLevel(level.price(), filled));
                remaining -= filled;
            }
        } catch (ArithmeticException e) {
            log.warn("event=market_order_pricing_failed stage=fill_amount_calculation stockCode={} side={} code=AMOUNT_OVERFLOW", stockCode, side);
            throw new OrderException("호가 또는 주문금액이 허용 범위를 초과합니다.");
        }
        if (remaining != 0) {
            log.warn("event=market_order_pricing_failed stage=liquidity_check stockCode={} side={} code=INSUFFICIENT_MARKET_LIQUIDITY", stockCode, side);
            throw new OrderException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_MARKET_LIQUIDITY",
                    "시장가 주문을 전량 체결할 상대 호가 잔량이 부족합니다.");
        }
        return new Estimate(amount, List.copyOf(fills));
    }

    public static void validateBook(String code, OrderBook book, Instant now) {
        if (book == null || !code.equals(book.stockCode()) || book.receivedAt() == null
                || book.quoteTime() == null || book.receivedAt().isAfter(now)
                || book.receivedAt().isBefore(now.minusSeconds(5))) {
            log.warn("event=market_order_pricing_failed stage=book_validation stockCode={} code=BOOK_IDENTITY_OR_RECEIVED_TIME_INVALID", code);
            throw unavailable();
        }
        var local = now.atZone(ZoneId.of("Asia/Seoul"));
        var quotedAt = local.toLocalDate().atTime(book.quoteTime()).atZone(local.getZone()).toInstant();
        if ((book.exchangeTimestamp() != null && !book.exchangeTimestamp().equals(quotedAt))
                || !book.receivedAt().atZone(local.getZone()).toLocalDate().equals(local.toLocalDate())
                || quotedAt.isBefore(now.minusSeconds(5)) || quotedAt.isAfter(now.plusSeconds(1))) {
            log.warn("event=market_order_pricing_failed stage=exchange_time_validation stockCode={} code=BOOK_EXCHANGE_TIME_INVALID", code);
            throw unavailable();
        }
    }
    private static OrderException unavailable() {
        return new OrderException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_DATA_UNAVAILABLE", "사용 가능한 최신 호가를 확인할 수 없습니다.");
    }
}
