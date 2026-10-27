package com.stock_spoon.river_be.order.messaging;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.execution.service.OrderExecutionService;
import com.stock_spoon.river_be.execution.service.MarketOrderPricingService;
import com.stock_spoon.river_be.market.QuoteEvent;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.entity.OrderRequestRecord;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.order.repository.OrderRequestRecordRepository;
import com.stock_spoon.river_be.order.reservation.OrderReservationStore;
import com.stock_spoon.river_be.order.reservation.OrderReservationStore.Reservation;
import com.stock_spoon.river_be.order.service.OrderService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** DB account lock serializes admissions, consumption, cancellation and execution. No SQS I/O inside. */
public class OrderRequestTransactions {
    private final AccountRepository accounts;
    private final OrderRequestRecordRepository records;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final OrderService orders;
    private final OrderExecutionService execution;
    private final OrderRequestValidator validator;
    private final OrderReservationStore store;
    private final MarketOrderPricingService pricing;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    public OrderRequestTransactions(AccountRepository accounts, OrderRequestRecordRepository records, EntityManager em,
            PlatformTransactionManager manager, OrderService orders, OrderExecutionService execution,
            OrderRequestValidator validator, OrderReservationStore store, MarketOrderPricingService pricing, Clock clock) {
        this.accounts=accounts; this.records=records; this.em=em; this.orders=orders; this.execution=execution;
        this.validator=validator; this.store=store; this.pricing=pricing; this.clock=clock;
        tx=new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Reservation admit(OrderRequestMessage message, QuoteEvent quote) {
        validator.validate(message);
        return tx.execute(status -> {
            lock(message.accountId());
            var done = existing(message);
            if (done != null) return null;
            orders.assertOrderableAccount(message.userId(), message.accountId(), message.source());
            var pending = live(message.accountId());
            for (var r : pending) if (r.message().eventId().equals(message.eventId())) {
                if (!r.message().sameRequest(message)) throw conflict();
                return r;
            }
            if (pending.size() >= 1000) throw new OrderException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ORDER_REQUESTS", "처리 중인 주문이 많습니다.");
            long amount = amount(message, quote);
            assertResources(message, amount, pending);
            var reservation = new Reservation(message, amount, null);
            store.add(reservation);
            return reservation;
        });
    }
    public OrderRequestRecord process(OrderRequestMessage message, QuoteEvent quote, Map<String, BigDecimal> prices, Runnable validateQuote) {
        return tx.execute(status -> {
            lock(message.accountId());
            var done = existing(message);
            if (done != null) return done;
            validator.validate(message);
            orders.assertOrderableAccount(message.userId(), message.accountId(), message.source());
            var pending = live(message.accountId());
            if (message.source() == Order.Source.USER && pending.stream().noneMatch(r -> r.message().sameRequest(message))) {
                throw new OrderException(HttpStatus.CONFLICT, "ORDER_RESERVATION_LOST", "주문 임시 예약을 확인할 수 없습니다.");
            }
            long amount = amount(message, quote);
            assertResources(message, amount, pending);
            Order order;
            if (message.market()) {
                order = execution.executeMarket(message.userId(), message.accountId(), message.request(), message.source(),
                        quote.orderBook(), prices, validateQuote);
            } else order = orders.reserveLimit(message.userId(), message.accountId(), message.request(), message.source());
            return persist(new OrderRequestRecord(message.eventId(), message.accountId(), message.userId(),
                    hash(message), order.getId(), null, clock.instant()));
        });
    }
    public OrderRequestRecord reject(OrderRequestMessage message, String code) {
        return tx.execute(status -> {
            var account=lock(message.accountId());
            var done = existing(message);
            if (done != null) return done;
            // [계좌 비활성화 수정] 처리 중 비활성화됐다면 원래 오류 대신 계좌 비활성 사유로 종결한다.
            return persist(new OrderRequestRecord(message.eventId(), message.accountId(), message.userId(),
                    hash(message), null, account.isActive()?code:"ACCOUNT_INACTIVE", clock.instant()));
        });
    }

    // [계좌 비활성화 추가] 외부 시세 확보 전 잠금 재검사한다. 기존 결과는 보존하고 미처리 요청만 거절로 기록한다.
    public OrderRequestRecord rejectIfInactive(OrderRequestMessage message) {
        return tx.execute(status -> {
            var account=lock(message.accountId());
            if(account.isActive()) return null;
            return inactiveResult(account,message);
        });
    }

    // [계좌 비활성화 추가] Redis만 지우기 전에 요청 결과를 DB에 커밋한다. 실패하면 예약이 남아 다음 reconcile에서 재시도한다.
    public boolean cleanupInactiveAccount(long accountId) {
        boolean inactive=Boolean.TRUE.equals(tx.execute(status -> {
            var account=lock(accountId);
            if(account.isActive()) return false;
            for(var reservation:store.list(accountId)) inactiveResult(account,reservation.message());
            return true;
        }));
        if(inactive) store.removeAll(accountId);
        return inactive;
    }

    // 예약/메시지가 실제 소유 계좌와 일치하는지 확인하고, 동일 eventId의 기존 결과·충돌 검증을 재사용한다.
    private OrderRequestRecord inactiveResult(Account account,OrderRequestMessage message) {
        if(account.getId()!=message.accountId() || !account.belongsTo(message.userId())) throw conflict();
        var done=existing(message);
        if(done!=null) return done;
        validator.validate(message);
        return persist(new OrderRequestRecord(message.eventId(),message.accountId(),message.userId(),
                hash(message),null,"ACCOUNT_INACTIVE",clock.instant()));
    }
    public OrderRequestRecord find(OrderRequestMessage message) {
        return tx.execute(status -> existing(message));
    }
    public void validate(OrderRequestMessage message) { validator.validate(message); }
    private OrderRequestRecord persist(OrderRequestRecord record) {
        // Assigned IDs must INSERT, never JPA merge over an event concurrently committed by another account.
        em.persist(record); em.flush(); return record;
    }
    public void cleanup(OrderRequestMessage message) {
        tx.executeWithoutResult(status -> { lock(message.accountId()); if (existing(message) != null) store.remove(message.accountId(), message.eventId()); });
    }
    public Map<String,Object> result(long userId, long accountId, String eventId) {
        return tx.execute(status -> {
            var account=accounts.findByIdAndUserIdAndActiveTrue(accountId,userId).orElseThrow(() ->
                    new OrderException(HttpStatus.FORBIDDEN,"FORBIDDEN_ACCOUNT","이 계좌에 접근할 권한이 없습니다."));
            var result=records.findById(eventId).orElse(null);
            if(result!=null && (result.accountId()!=accountId || result.userId()!=userId)) throw conflict();
            if(result!=null && result.accountId()==account.getId() && result.userId()==userId) {
                Map<String,Object> response=new LinkedHashMap<>();
                response.put("request_id",eventId); response.put("status",result.status().toLowerCase(Locale.ROOT));
                response.put("order_id",result.orderId()); response.put("reason_code",result.reasonCode()); return response;
            }
            if(store.list(accountId).stream().anyMatch(r -> r.message().eventId().equals(eventId) && r.message().userId()==userId))
                return Map.of("request_id",eventId,"status","processing");
            throw new OrderException(HttpStatus.NOT_FOUND,"ORDER_REQUEST_NOT_FOUND","주문 요청을 찾을 수 없습니다.");
        });
    }
    // [계좌 비활성화 수정] 잠금 계좌를 반환해 모든 처리 경로가 refresh한 최신 active 값을 검사할 수 있게 한다.
    private Account lock(long accountId) {
        var account=accounts.findLockedById(accountId).orElseThrow(() -> new OrderException("계좌를 찾을 수 없습니다."));
        em.refresh(account);
        return account;
    }
    private List<Reservation> live(long accountId) {
        var all=store.list(accountId);
        if(all.isEmpty()) return all;
        var done=new HashSet<String>();
        records.findByAccountIdAndEventIdIn(accountId,all.stream().map(r->r.message().eventId()).toList())
                .forEach(r->done.add(r.eventId()));
        return all.stream().filter(r->!done.contains(r.message().eventId())).toList();
    }
    private long amount(OrderRequestMessage message, QuoteEvent quote) {
        if(message.market()) {
            var now=clock.instant(); var local=now.atZone(ZoneId.of("Asia/Seoul"));
            if(message.expired(now)) throw new OrderException(HttpStatus.CONFLICT,"ORDER_REQUEST_EXPIRED","시장가 요청의 거래 시간이 종료되었습니다.");
            if(local.getDayOfWeek()==DayOfWeek.SATURDAY || local.getDayOfWeek()==DayOfWeek.SUNDAY
                    || local.toLocalTime().isBefore(LocalTime.of(9,0)) || !local.toLocalTime().isBefore(LocalTime.of(15,30)))
                throw new OrderException(HttpStatus.CONFLICT,"ORDER_WINDOW_CLOSED","시장가 주문 가능 시간이 아닙니다.");
            long estimate=pricing.estimate(message.request().stockCode(), message.side(), message.request().quantity(),
                    quote==null?null:quote.orderBook(),now).amount();
            return message.side()==Order.Side.BUY?estimate:0;
        }
        try { return message.side()==Order.Side.BUY?Math.multiplyExact(message.request().quantity(),message.request().limitPrice()):0; }
        catch(ArithmeticException e) { throw new OrderException("주문금액이 허용 범위를 초과합니다."); }
    }
    private void assertResources(OrderRequestMessage m,long amount,List<Reservation> pending) {
        long other=0;
        try {
            for(var r:pending) {
                if(r.message().eventId().equals(m.eventId())) continue;
                if(m.side()==Order.Side.BUY && r.message().side()==Order.Side.BUY) other=Math.addExact(other,r.amount());
                if(m.side()==Order.Side.SELL && r.message().side()==Order.Side.SELL
                        && r.message().request().stockCode().equals(m.request().stockCode())) other=Math.addExact(other,r.message().request().quantity());
            }
            long available=m.side()==Order.Side.BUY?orders.availableCash(m.accountId()):orders.sellableQuantity(m.accountId(),m.request().stockCode());
            if(Math.subtractExact(available,other)<(m.side()==Order.Side.BUY?amount:m.request().quantity()))
                throw new OrderException(HttpStatus.BAD_REQUEST,m.side()==Order.Side.BUY?"INSUFFICIENT_CASH":"INSUFFICIENT_SELLABLE_QUANTITY",
                        m.side()==Order.Side.BUY?"주문 가능 현금이 부족합니다.":"매도 가능 수량이 부족합니다.");
        } catch(ArithmeticException e) { throw new OrderException("예약 금액·수량이 허용 범위를 초과합니다."); }
    }
    private OrderRequestRecord existing(OrderRequestMessage m) {
        var found=records.findById(m.eventId()).orElse(null);
        if(found!=null && (found.accountId()!=m.accountId() || found.userId()!=m.userId() || !found.payloadHash().equals(hash(m)))) {
            if(found.accountId()!=m.accountId() && accounts.findLockedById(m.accountId()).isPresent()) {
                // Only the losing account's exact request may be released; never alter the winning record.
                for(var r:store.list(m.accountId())) if(r.message().sameRequest(m)) store.remove(m.accountId(),m.eventId());
            }
            throw conflict();
        }
        return found;
    }
    public String hash(OrderRequestMessage m) {
        try {
            var bytes=json.writeValueAsBytes(List.of(m.userId(),m.accountId(),m.source(),m.request()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static OrderException conflict() { return new OrderException(HttpStatus.CONFLICT,"ORDER_REQUEST_CONFLICT","같은 요청 ID에 다른 주문 내용이 있습니다."); }
}
