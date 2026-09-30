package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 외부 시세와 주문 체결을 연결한다. 주문별 트랜잭션은 별도 서비스 프록시가 담당한다. */
@Service
public class OrderExecutionListener {
    private static final Logger log = LoggerFactory.getLogger(OrderExecutionListener.class);
    private final OrderRepository orders;
    private final ExecutionRepository executions;
    private final OrderExecutionService execution;
    private final OrderSubscriptionService subscriptions;
    private final KiwoomStockStream stream;
    private final KiwoomMarketClient market;

    public OrderExecutionListener(OrderRepository orders, ExecutionRepository executions,
            OrderExecutionService execution, OrderSubscriptionService subscriptions,
            KiwoomStockStream stream, KiwoomMarketClient market) {
        this.orders = orders;
        this.executions = executions;
        this.execution = execution;
        this.subscriptions = subscriptions;
        this.stream = stream;
        this.market = market;
    }

    /** 저장이 커밋된 뒤 최신 보관 가격 또는 REST 현재가로 이 주문을 먼저 판단한다. */
    public void orderCreated(Order order) {
        try {
            var latest = stream.latest(order.getStockCode());
            long price;
            if (latest.isPresent()) {
                price = latest.get().currentPrice().longValueExact();
            } else {
                long fetched = market.currentPrice(order.getStockCode());
                // REST 조회 중 웹소켓 가격이 도착했다면 그 값을 우선한다. REST로 캐시를 덮어쓰지 않는다.
                price = stream.latest(order.getStockCode())
                        .map(value -> value.currentPrice().longValueExact()).orElse(fetched);
            }
            attempt(order, price);
        } catch (RuntimeException error) {
            log.warn("주문 {}의 초기 현재가 조회에 실패했습니다. 웹소켓 시세를 기다립니다.", order.getId());
        } finally {
            subscriptions.refresh();
        }
    }

    @EventListener
    public void onPrice(KiwoomStockStream.StockPrice price) {
        boolean executed = false;
        try {
            long currentPrice = price.currentPrice().longValueExact();
            for (Order order : orders.findPendingForPrice(price.stockCode(), Order.Status.PENDING, Order.Type.LIMIT)) {
                // 주문 접수 전에 수신된 이벤트가 나중에 처리되어 새 주문을 체결하지 않게 한다.
                if (!order.getCreatedAt().isAfter(price.receivedAt())) executed |= attempt(order, currentPrice);
            }
        } catch (RuntimeException error) {
            log.warn("종목 {}의 대기 주문 조회 또는 가격 처리에 실패했습니다. 다음 시세에서 재시도합니다.",
                    price.stockCode());
        } finally {
            if (executed) subscriptions.refresh();
        }
    }

    private boolean attempt(Order order, long price) {
        try {
            return execution.executeLimit(order.getAccountId(), order.getId(), price);
        } catch (RuntimeException error) {
            // 한 주문의 롤백이 다른 주문 처리를 막지 않는다. 원문/인증정보는 로그에 남기지 않는다.
            log.warn("주문 {} 체결 처리에 실패했습니다. 다음 시세에서 재시도합니다.", order.getId());
            return false;
        }
    }

    @Transactional(readOnly = true)
    public OrderCreateResponse response(long accountId, long orderId) {
        var order = orders.findByIdAndAccountId(orderId, accountId).orElseThrow();
        return OrderCreateResponse.from(order, executions.findForOrder(orderId));
    }
}