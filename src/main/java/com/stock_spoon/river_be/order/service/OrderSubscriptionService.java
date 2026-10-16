package com.stock_spoon.river_be.order.service;

import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.market.MarketStream;
import java.util.HashSet;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/** DB의 대기 주문이 구독 수요를 소유한다. 시세 연결 실패는 예약 접수를 막지 않는다. */
@Service
public class OrderSubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(OrderSubscriptionService.class);
    private final OrderRepository orders;
    private final MarketStream stream;

    public OrderSubscriptionService(OrderRepository orders, MarketStream stream) {
        this.orders = orders;
        this.stream = stream;
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
            stream.updateSymbols(new HashSet<>(orders.findStockCodesByStatus(Order.Status.PENDING)));
        } catch (RuntimeException error) {
            // 주문은 DB에 유지된다. 다음 주기에 구독을 재시도한다.
            log.warn("event=order_subscription_refresh_failed causeType={}", error.getClass().getSimpleName());
        }
    }
}
