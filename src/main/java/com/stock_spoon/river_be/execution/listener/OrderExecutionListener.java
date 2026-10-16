package com.stock_spoon.river_be.execution.listener;

import com.stock_spoon.river_be.order.dto.OrderCreateResponse;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.execution.repository.ExecutionRepository;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.execution.service.OrderBuyPriceService;
import com.stock_spoon.river_be.execution.service.OrderExecutionService;
import com.stock_spoon.river_be.order.service.OrderSubscriptionService;

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
    private final com.stock_spoon.river_be.market.MarketStream stream;
    private final OrderBuyPriceService buyPrices;

    public OrderExecutionListener(OrderRepository orders, ExecutionRepository executions,
            OrderExecutionService execution, OrderSubscriptionService subscriptions,
            com.stock_spoon.river_be.market.MarketStream stream, KiwoomMarketClient market, OrderBuyPriceService buyPrices) {
        this.orders = orders;
        this.executions = executions;
        this.execution = execution;
        this.subscriptions = subscriptions;
        this.stream = stream;
        this.buyPrices = buyPrices;
    }

    /** 저장이 커밋된 뒤 연결 식별자를 유지한 최신 시세로 이 주문을 먼저 판단한다. */
    // [체결 진입 A] Controller가 저장 후 직접 호출한다. 현재가 확보 후 attempt()로 이어진다.
    public void orderCreated(Order order) {
        try {
            // 장외·이전 거래일의 예약은 새 이벤트로만 체결 판단한다.
            if (!execution.isCurrentSession(order.getCreatedAt())) return;
            stream.assertAvailable(order.getStockCode());
            var latest = stream.latestQuote(order.getStockCode());
            if (latest.isEmpty() || !execution.isUsablePrice(latest.get().price())) return;
            var quote = latest.get();
            Runnable validate = () -> {
                if (!execution.isUsablePrice(quote.price())) throw new IllegalStateException("유효한 장중 시세가 아닙니다.");
                stream.assertSource(order.getStockCode(), quote.source());
            };
            attempt(order, quote.price().currentPrice().longValueExact(), validate);
        } catch (RuntimeException error) {
            log.warn("event=order_initial_price_failed orderId={} causeType={}", order.getId(), error.getClass().getSimpleName());
        } finally {
            subscriptions.refresh();
        }
    }

    // [체결 진입 B] 새 StockPrice 이벤트가 오면 HTTP 요청 없이도 Spring이 onPrice()를 호출한다.
    // RedisMarketStream이 유효한 종목별 Pub/Sub 알림을 Spring 이벤트로 전달한다.
    @EventListener
    public void onPrice(KiwoomStockStream.StockPrice price) {
        processPrice(price, () -> stream.assertAvailable(price.stockCode()));
    }

    @EventListener
    public void onQuote(com.stock_spoon.river_be.market.RedisQuoteStore.QuoteEvent event) {
        if (!"price".equals(event.type()) || event.price() == null) return;
        processPrice(event.price(), () -> stream.assertSource(event.stockCode(), event.source()));
    }

    private void processPrice(KiwoomStockStream.StockPrice price, Runnable validate) {
        if (!execution.isUsablePrice(price)) return;
        Runnable currentSource = () -> {
            if (!execution.isUsablePrice(price)) throw new IllegalStateException("유효한 장중 시세가 아닙니다.");
            validate.run();
        };
        boolean executed = false;
        try {
            currentSource.run();
            long currentPrice = price.currentPrice().longValueExact();
            for (Order order : orders.findPendingForPrice(price.stockCode(), Order.Status.PENDING, Order.Type.LIMIT)) {
                // 주문 접수 전에 수신된 이벤트가 나중에 처리되어 새 주문을 체결하지 않게 한다.
                if (!order.getCreatedAt().isAfter(price.receivedAt())) executed |= attempt(order, currentPrice, currentSource);
            }
        } catch (RuntimeException error) {
            log.warn("event=order_price_processing_failed stockCode={} causeType={}",
                    price.stockCode(), error.getClass().getSimpleName());
        } finally {
            if (executed) subscriptions.refresh();
        }
    }

    private boolean attempt(Order order, long price, Runnable validate) {
        try {
            // 가격 미충족 주문은 잠금을 생략하고 예약 상태를 유지한다.
            if (price > 0 && (order.getSide() == Order.Side.BUY && price > order.getLimitPrice()
                    || order.getSide() == Order.Side.SELL && price < order.getLimitPrice())) return false;
            validate.run();
            // 최초 판단과 후속 시세 처리가 같은 체결 서비스를 재사용한다.
            // 여기의 execution은 OrderExecutionService이며 별도 서비스 호출로 체결 트랜잭션이 시작된다.
            boolean executed;
            if (order.getSide() == Order.Side.BUY && price <= order.getLimitPrice()) {
                var prices = buyPrices.fetch(order.getAccountId(), order.getStockCode());
                validate.run();
                executed = execution.executeLimit(order.getAccountId(), order.getId(), price, prices, validate);
            } else {
                executed = execution.executeLimit(order.getAccountId(), order.getId(), price, java.util.Map.of(), validate);
            }
            if (executed) log.info("event=order_executed orderId={} stockCode={} type=LIMIT side={}",
                    order.getId(), order.getStockCode(), order.getSide());
            return executed;
        } catch (RuntimeException error) {
            // 한 주문의 롤백이 다른 주문 처리를 막지 않는다. 원문/인증정보는 로그에 남기지 않는다.
            log.warn("event=order_execution_failed orderId={} causeType={}", order.getId(), error.getClass().getSimpleName());
            return false;
        }
    }

    @Transactional(readOnly = true)
    // DB에서 주문·체결을 다시 읽고 응답 DTO로 옮긴다. Entity를 그대로 HTTP 응답에 노출하지 않는다.
    public OrderCreateResponse response(long accountId, long orderId) {
        var order = orders.findByIdAndAccountId(orderId, accountId).orElseThrow();
        return OrderCreateResponse.from(order, executions.findForOrder(orderId));
    }
}
