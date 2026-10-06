package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/** 주문 접수 중인 수요를 DB 커밋까지 유지하고, 이후에는 PENDING 주문이 구독을 소유한다. */
@Service
public class OrderSubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(OrderSubscriptionService.class);
    private final OrderRepository orders;
    private final KiwoomStockStream stream;
    private final OrderService orderService;
    // ponytail: v1은 단일 BE. 여러 인스턴스에서는 구독 담당과 시세 공유를 먼저 설계한다.
    private final Map<String, Integer> accepting = new HashMap<>();

    public OrderSubscriptionService(OrderRepository orders, KiwoomStockStream stream,
            OrderService orderService) {
        this.orders = orders;
        this.stream = stream;
        this.orderService = orderService;
    }

    public Order create(String stockCode, Supplier<Order> saveOrder) {
        return create(stockCode, saveOrder, Duration.ofSeconds(10));
    }

    Order create(String stockCode, Supplier<Order> saveOrder, Duration timeout) {
        try {
            retain(stockCode).get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            // 별도 OrderService 프록시의 트랜잭션이 커밋된 뒤에 임시 수요를 해제한다.
            // 위 구독 대기가 성공한 뒤 Controller에서 전달한 람다를 실행한다.
            // Supplier<Order>의 get()은 DB 조회가 아니라 전달받은 작업을 실행하고 Order를 받는 호출이다.
            Order saved = saveOrder.get();
            if (saved != null) {
                log.info("event=order_created orderId={} stockCode={} type={} side={} status={}",
                        saved.getId(), saved.getStockCode(), saved.getType(), saved.getSide(), saved.getStatus());
                if (saved.getStatus() == Order.Status.EXECUTED) {
                    log.info("event=order_executed orderId={} stockCode={} type={} side={}",
                            saved.getId(), saved.getStockCode(), saved.getType(), saved.getSide());
                }
            }
            return saved;
        } catch (InterruptedException error) {
            log.warn("event=order_subscription_failed stockCode={} causeType=InterruptedException", stockCode);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException error) {
            log.warn("event=order_subscription_failed stockCode={} causeType={}", stockCode, error.getClass().getSimpleName());
            throw unavailable();
        } finally {
            release(stockCode);
        }
    }

    private synchronized CompletableFuture<Void> retain(String stockCode) {
        accepting.merge(stockCode, 1, Integer::sum);
        synchronizeSymbols();
        return stream.whenSubscribed(stockCode);
    }

    private synchronized void release(String stockCode) {
        accepting.computeIfPresent(stockCode, (ignored, count) -> count == 1 ? null : count - 1);
        refresh();
    }

    /** 시작 시 복구 및 취소/체결 이후 누락된 수요 변경을 기존 5초 유지 주기에 맞춰 반영한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void restorePendingSubscriptions() {
        refresh();
    }

    @Scheduled(fixedDelay = 5000)
    public synchronized void refresh() {
        try {
            orderService.cancelExpiredPendingOrders();
            synchronizeSymbols();
        } catch (RuntimeException error) {
            // DB 정리/갱신 실패 시 기존 구독을 보존하고 다음 주기에 재시도한다.
            log.warn("event=order_subscription_refresh_failed causeType={}", error.getClass().getSimpleName());
        }
    }

    private void synchronizeSymbols() {
        var desired = new HashSet<>(orders.findStockCodesByStatus(Order.Status.PENDING));
        desired.addAll(accepting.keySet());
        stream.updateSymbols(desired);
    }

    private OrderException unavailable() {
        return new OrderException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_STREAM_UNAVAILABLE",
                "종목 시세 구독을 확인하지 못해 주문을 접수하지 않았습니다.");
    }
}
