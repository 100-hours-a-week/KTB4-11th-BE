package com.stock_spoon.river_be.order.service;

import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.market.MarketStream;
import com.stock_spoon.river_be.market.QuoteEvent;
import com.stock_spoon.river_be.order.exception.OrderException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;

/** 대기 주문과 처리 중인 시장가 요청의 구독 수요를 유지한다. 시세 연결 실패는 지정가 예약 접수를 막지 않는다. */
@Service
public class OrderSubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(OrderSubscriptionService.class);
    private final OrderRepository orders;
    private final MarketStream stream;
    private final Map<String, Integer> marketRequests = new HashMap<>();
    private final long quoteTimeoutNanos;

    @org.springframework.beans.factory.annotation.Autowired
    public OrderSubscriptionService(OrderRepository orders, MarketStream stream) {
        this(orders, stream, Duration.ofSeconds(3));
    }

    OrderSubscriptionService(OrderRepository orders, MarketStream stream, Duration quoteTimeout) {
        this.orders = orders;
        this.stream = stream;
        this.quoteTimeoutNanos = quoteTimeout.toNanos();
    }

    public Order withMarketQuote(String stockCode, Function<QuoteEvent, Order> execute) {
        return withQuoteSnapshot(stockCode, execute);
    }

    public <T> T withQuoteSnapshot(String stockCode, Function<QuoteEvent, T> execute) {
        synchronized (this) {
            marketRequests.merge(stockCode, 1, Integer::sum);
        }
        try {
            try {
                synchronized (this) { updateSubscriptions(); }
            } catch (RuntimeException error) {
                throw unavailableMarketStream();
            }
            return execute.apply(awaitMarketQuote(stockCode));
        } finally {
            synchronized (this) {
                marketRequests.computeIfPresent(stockCode, (code, count) -> count == 1 ? null : count - 1);
                refresh();
            }
        }
    }

    private QuoteEvent awaitMarketQuote(String stockCode) {
        long started = System.nanoTime();
        while (true) {
            try {
                var latest = stream.latestBookQuote(stockCode);
                if (latest.isPresent()) {
                    var quote = latest.get();
                    if (!stockCode.equals(quote.stockCode()) || !"orderbook".equals(quote.type())
                            || quote.orderBook() == null || quote.source() == null) throw unavailableMarketStream();
                    stream.assertSource(stockCode, quote.source());
                    return quote;
                }
            } catch (RuntimeException error) {
                throw unavailableMarketStream();
            }
            long remaining = quoteTimeoutNanos - (System.nanoTime() - started);
            if (remaining <= 0) throw unavailableMarketStream();
            try {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(remaining, Duration.ofMillis(50).toNanos()));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw unavailableMarketStream();
            }
        }
    }

    private static OrderException unavailableMarketStream() {
        return new OrderException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_STREAM_UNAVAILABLE",
                "사용 가능한 최신 호가를 확인할 수 없습니다.");
    }

    private void updateSubscriptions() {
        var symbols = new HashSet<>(orders.findStockCodesByStatus(Order.Status.PENDING));
        symbols.addAll(marketRequests.keySet());
        stream.updateSymbols(symbols);
    }

    public Order create(String stockCode, Supplier<Order> saveOrder) {
        // 별도 OrderService 프록시가 예약을 커밋한 뒤 구독한다. DB 실패는 호출자에게 전파한다.
        Order saved = saveOrder.get();
        if (saved != null) {
            log.info("event=order_created orderId={} stockCode={} type={} side={} status={}",
                    saved.getId(), stockCode, saved.getType(), saved.getSide(), saved.getStatus());
        }
        refresh();
        return saved;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restorePendingSubscriptions() { refresh(); }

    @Scheduled(fixedDelay = 5000)
    public synchronized void refresh() {
        try {
            updateSubscriptions();
        } catch (RuntimeException error) {
            // 주문은 DB에 유지된다. 다음 주기에 구독을 재시도한다.
            log.warn("event=order_subscription_refresh_failed causeType={}", error.getClass().getSimpleName());
        }
    }
}
