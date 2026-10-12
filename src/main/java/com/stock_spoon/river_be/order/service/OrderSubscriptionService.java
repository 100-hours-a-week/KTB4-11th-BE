package com.stock_spoon.river_be.order.service;

import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.order.repository.OrderRepository;

import com.stock_spoon.river_be.market.MarketStream;
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
    private final MarketStream stream;
    private final OrderService orderService;
    // 이 서버에서 진행 중인 요청을 추적한다. 다른 서버와 공유할 수요는 Redis에 별도 저장한다.
    private final Map<String, String> accepting = new HashMap<>();

    public OrderSubscriptionService(OrderRepository orders, MarketStream stream,
            OrderService orderService) {
        this.orders = orders;
        this.stream = stream;
        this.orderService = orderService;
    }

    public Order create(String stockCode, Supplier<Order> saveOrder) {
        return create(stockCode, saveOrder, Duration.ofSeconds(10));
    }

    Order create(String stockCode, Supplier<Order> saveOrder, Duration timeout) {
        String requestId = java.util.UUID.randomUUID().toString();
        try {
            retain(requestId, stockCode).get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            try {
                stream.assertRequest(requestId, stockCode);
                stream.assertAvailable(stockCode);
            } catch (RuntimeException error) {
                throw unavailable();
            }
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
            release(requestId, stockCode);
        }
    }

    private synchronized CompletableFuture<Void> retain(String id, String stockCode) {
        try {
            stream.retainRequest(id, stockCode);
            accepting.put(id, stockCode);
            synchronizeSymbols();
            return stream.whenSubscribed(stockCode);
        } catch (RuntimeException error) {
            throw unavailable();
        }
    }

    private synchronized void release(String id, String stockCode) {
        accepting.remove(id);
        refresh();
        try {
            stream.releaseRequest(id, stockCode);
        } catch (RuntimeException error) {
            // 커밋된 주문 응답을 정리 실패로 바꾸지 않는다. 임시 수요는 TTL로 정리된다.
            log.warn("event=order_demand_release_failed causeType={}", error.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelay = 10000)
    public synchronized void renewAcceptingRequests() {
        accepting.forEach((id, code) -> {
            try {
                stream.renewRequest(id, code);
            } catch (RuntimeException error) {
                log.warn("event=order_demand_renew_failed stockCode={} causeType={}", code, error.getClass().getSimpleName());
            }
        });
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
        desired.addAll(accepting.values());
        stream.updateSymbols(desired);
    }

    private OrderException unavailable() {
        return new OrderException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_STREAM_UNAVAILABLE",
                "종목 시세 구독을 확인하지 못해 주문을 접수하지 않았습니다.");
    }
}
