package com.stock_spoon.river_be.order.messaging;

import com.stock_spoon.river_be.execution.listener.OrderExecutionListener;
import com.stock_spoon.river_be.execution.service.OrderBuyPriceService;
import com.stock_spoon.river_be.market.MarketStream;
import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.entity.OrderRequestRecord;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.order.reservation.OrderReservationStore;
import com.stock_spoon.river_be.order.service.OrderSubscriptionService;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

public class OrderRequestService {
    private static final Logger log=LoggerFactory.getLogger(OrderRequestService.class);
    private final OrderRequestTransactions transactions;
    private final OrderReservationStore store;
    private final OrderRequestPublisher publisher;
    private final OrderSubscriptionService subscriptions;
    private final MarketStream stream;
    private final OrderBuyPriceService prices;
    private final OrderRepository orders;
    private final OrderExecutionListener execution;
    private final Clock clock;
    public OrderRequestService(OrderRequestTransactions transactions,OrderReservationStore store,OrderRequestPublisher publisher,
            OrderSubscriptionService subscriptions,MarketStream stream,OrderBuyPriceService prices,OrderRepository orders,
            OrderExecutionListener execution,Clock clock) {
        this.transactions=transactions; this.store=store; this.publisher=publisher; this.subscriptions=subscriptions;
        this.stream=stream; this.prices=prices; this.orders=orders; this.execution=execution; this.clock=clock;
    }
    public Map<String,String> submit(long userId,long accountId,String eventId,OrderCreateRequest request) {
        OrderRequestMessage m;
        try { m=new OrderRequestMessage(eventId,eventId,userId,accountId,Order.Source.USER,clock.instant(),request); }
        catch(IllegalArgumentException e) { throw new OrderException("Idempotency-Key 요청 ID를 확인하세요."); }
        transactions.validate(m);
        // Check an earlier result before obtaining a quote; a retry must not depend on current market data.
        if(transactions.find(m)!=null) return Map.of("request_id",eventId,"status","accepted");
        OrderReservationStore.Reservation reservation;
        if(m.market()) reservation=subscriptions.withQuoteSnapshot(request.stockCode(),q->{
            stream.assertSource(request.stockCode(),q.source()); return transactions.admit(m,q);
        });
        else reservation=transactions.admit(m,null);
        if(reservation!=null) publish(reservation);
        return Map.of("request_id",eventId,"status","accepted");
    }
    public void publish(OrderReservationStore.Reservation r) {
        String messageId;
        try { messageId=publisher.publish(r.message()); }
        catch(RuntimeException e) {
            // Keep the reservation and original payload: network errors may follow a successful send.
            throw new OrderException(HttpStatus.SERVICE_UNAVAILABLE,"ORDER_PUBLICATION_UNCERTAIN",
                    "주문 전송을 확인 중입니다. 같은 요청 ID로 결과를 확인하세요.");
        }
        log.info("event=order_request_published messageId={} correlationId={} eventId={}",messageId,r.message().correlationId(),r.message().eventId());
        // If this fails, recovery can republish using the same ID; the message has already been accepted.
        try { store.published(r,clock.instant()); }
        catch(RuntimeException e) { log.warn("event=order_publish_marker_failed eventId={}",r.message().eventId()); }
    }
    public OrderRequestRecord consume(OrderRequestMessage m) {
        // [계좌 비활성화 추가] 시세 장애·장외 여부와 무관하게 비활성 계좌의 메시지를 먼저 종결한다.
        var inactive=transactions.rejectIfInactive(m);
        if(inactive!=null) {
            transactions.cleanup(m);
            return inactive;
        }
        var done=transactions.find(m);
        if(done==null) {
            try {
                transactions.validate(m);
                if(m.expired(clock.instant())) done=transactions.reject(m,"ORDER_REQUEST_EXPIRED");
                else if(m.market()) {
                    var valuation=m.source()==Order.Source.AI && m.side()==Order.Side.BUY
                            ?prices.fetch(m.accountId(),m.request().stockCode()):Map.<String,java.math.BigDecimal>of();
                    done=subscriptions.withQuoteSnapshot(m.request().stockCode(),q->transactions.process(m,q,valuation,
                            ()->stream.assertSource(m.request().stockCode(),q.source())));
                } else done=transactions.process(m,null,Map.of(),()->{});
            } catch(OrderException e) {
                // 최초 검사와 실제 처리 사이에 비활성화된 경우도 ACCOUNT_INACTIVE로 종결한다.
                var rejected=transactions.rejectIfInactive(m);
                if(rejected!=null) done=rejected;
                else {
                    if(!e.status().is4xxClientError() || "ORDER_REQUEST_CONFLICT".equals(e.code())) throw e;
                    done=transactions.reject(m,e.code());
                }
            } catch(org.springframework.dao.DataIntegrityViolationException e) {
                done=transactions.find(m); if(done==null) throw e;
            }
        }
        transactions.cleanup(m);
        if(done.orderId()!=null && !m.market()) {
            subscriptions.refresh();
            execution.orderCreated(orders.findById(done.orderId()).orElseThrow());
        }
        log.info("event=order_request_processed correlationId={} eventId={} status={} orderId={}",m.correlationId(),m.eventId(),done.status(),done.orderId());
        return done;
    }
    public Map<String,Object> result(long userId,long accountId,String eventId) { return transactions.result(userId,accountId,eventId); }
    public void reconcile() {
        for(long account:store.accounts()) {
            // [계좌 비활성화 추가] 미발행 예약도 거절 결과를 기록한 뒤 정리하고, SQS 재발행은 생략한다.
            try { if(transactions.cleanupInactiveAccount(account)) continue; }
            catch(RuntimeException error) {
                log.warn("event=inactive_order_cleanup_failed accountId={} causeType={}",account,error.getClass().getSimpleName());
                continue;
            }
            for(var r:store.list(account)) {
                try {
                    if(transactions.find(r.message())!=null || r.message().expired(clock.instant())) consume(r.message());
                    // Confirmed sends belong to SQS/DLQ redrive; never bypass DLQ by republishing them.
                    else if(r.publishedAt()==null) publish(r);
                } catch(RuntimeException e) { log.warn("event=order_request_reconciliation_failed eventId={} causeType={}",r.message().eventId(),e.getClass().getSimpleName()); }
            }
        }
    }
}
