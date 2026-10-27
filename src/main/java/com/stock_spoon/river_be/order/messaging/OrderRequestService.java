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
        OrderRequestRecord done;
        try {
            // [충돌 해결] 삭제된 계좌를 먼저 걸러낸 뒤 남아 있는 비활성 계좌의 거절 결과를 기록한다.
            if (transactions.discardIfAccountDeleted(m.accountId())) return null;
            // [계좌 비활성화 추가] 시세 장애·장외 여부와 무관하게 비활성 계좌의 메시지를 먼저 종결한다.
            var inactive=transactions.rejectIfInactive(m);
            if(inactive!=null) {
                transactions.cleanup(m);
                return inactive;
            }
            done=transactions.find(m);
            if(done==null) {
                transactions.validate(m);
                if(m.expired(clock.instant())) done=transactions.reject(m,"ORDER_REQUEST_EXPIRED");
                else if(m.market()) {
                    var valuation=m.source()==Order.Source.AI && m.side()==Order.Side.BUY
                            ?prices.fetch(m.accountId(),m.request().stockCode()):Map.<String,java.math.BigDecimal>of();
                    done=subscriptions.withQuoteSnapshot(m.request().stockCode(),q->transactions.process(m,q,valuation,
                            ()->stream.assertSource(m.request().stockCode(),q.source())));
                } else done=transactions.process(m,null,Map.of(),()->{});
            }
        } catch(OrderException e) {
            // [회원 탈퇴 수정] 최초 확인 후 처리 중 삭제됐을 수도 있어 계좌 부재를 다시 확인한다.
            if (transactions.discardIfAccountDeleted(m.accountId())) return null;
            // [충돌 해결] 시세 장애 중 비활성화된 경우도 먼저 종결하고, 삭제 경합은 아래에서 다시 확인한다.
            try {
                var rejected=transactions.rejectIfInactive(m);
                if(rejected!=null) done=rejected;
                else {
                    if(!e.status().is4xxClientError() || "ORDER_REQUEST_CONFLICT".equals(e.code())) throw e;
                    done=transactions.reject(m,e.code());
                }
            }
            catch (OrderException rejection) {
                // [회원 탈퇴 수정] 거절 이력 저장 직전에 삭제돼도 새 이력을 만들거나 무한 재시도하지 않는다.
                if (transactions.discardIfAccountDeleted(m.accountId())) return null;
                throw rejection;
            }
        } catch(org.springframework.dao.DataIntegrityViolationException e) {
            done=transactions.find(m); if(done==null) throw e;
        }
        transactions.cleanup(m);
        if(done.orderId()!=null && !m.market()) {
            subscriptions.refresh();
            // [회원 탈퇴 수정] 주문 커밋 직후 삭제됐다면 초기 체결 알림도 생략한다.
            orders.findById(done.orderId()).ifPresent(execution::orderCreated);
        }
        log.info("event=order_request_processed correlationId={} eventId={} status={} orderId={}",m.correlationId(),m.eventId(),done.status(),done.orderId());
        return done;
    }
    public Map<String,Object> result(long userId,long accountId,String eventId) { return transactions.result(userId,accountId,eventId); }
    public void reconcile() {
        for(long account:store.accounts()) {
            // [충돌 해결] 삭제된 계좌의 예약은 폐기하고 비활성 계좌는 거절 결과를 커밋한 뒤 정리한다.
            try {
                if (transactions.discardIfAccountDeleted(account)) continue;
                if (transactions.cleanupInactiveAccount(account)) continue;
            } catch (RuntimeException error) {
                // DB/Redis 장애를 계좌 부재로 오인하지 않고 로그를 남긴 뒤 다음 정리 주기에 다시 시도한다.
                log.warn("event=order_request_reconciliation_failed accountId={} causeType={}", account, error.getClass().getSimpleName());
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
