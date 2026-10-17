package com.stock_spoon.river_be.order.service;

import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.holding.entity.Holding;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.report.entity.ReportReasoning;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.holding.repository.HoldingRepository;
import com.stock_spoon.river_be.order.repository.OrderRepository;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 내부 주문 예약·취소 규칙. HTTP 인증과 주문 생성 조건 검증은 컨트롤러에서 선행한다. */
@Service
public class OrderService {
    private final AccountRepository accounts;
    private final OrderRepository orders;
    private final HoldingRepository holdings;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public OrderService(AccountRepository accounts, OrderRepository orders, HoldingRepository holdings) {
        this(accounts, orders, holdings, Clock.systemUTC());
    }

    OrderService(AccountRepository accounts, OrderRepository orders, HoldingRepository holdings, Clock clock) {
        this.accounts = accounts;
        this.orders = orders;
        this.holdings = holdings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public void assertOrderableAccount(long userId, long accountId) {
        assertOrderableAccount(userId, accountId, Order.Source.AI);
    }

    @Transactional(readOnly = true)
    public void assertOrderableAccount(long userId, long accountId, Order.Source source) {
        Account account = accounts.findByIdAndUserIdAndActiveTrue(accountId, userId)
                .orElseThrow(() -> new OrderException(HttpStatus.FORBIDDEN,
                        "FORBIDDEN_ACCOUNT", "이 계좌에 주문할 권한이 없습니다."));
        assertAccountSource(account, source);
        assertCompetitionStarted(accountId);
    }

    @Transactional
    public Order reserveLimit(long accountId, String stockCode, Order.Side side,
            long quantity, long limitPrice, String reason) {
        return reserveLimit(null, accountId, stockCode, side, quantity, limitPrice,
                reason);
    }

    @Transactional
    public Order reserveLimit(long userId, long accountId, String stockCode, Order.Side side,
            long quantity, long limitPrice, String reason) {
        return reserveLimit(Long.valueOf(userId), accountId, stockCode, side, quantity,
                limitPrice, reason);
    }

    @Transactional
    // [예약 진입점] DTO를 내부 메서드에 전달한다. Spring 프록시를 거쳐 들어올 때 트랜잭션이 시작된다.
    // 아래 private 메서드는 같은 트랜잭션 안에서 실행되고 정상 종료 시 커밋한다.
    public Order reserveLimit(long userId, long accountId, OrderCreateRequest request) {
        return reserveLimit(userId, accountId, request, Order.Source.AI);
    }

    @Transactional
    public Order reserveLimit(long userId, long accountId, OrderCreateRequest request, Order.Source source) {
        return reserveLimit(Long.valueOf(userId), accountId, request.stockCode(),
                Order.Side.valueOf(request.orderSide().toUpperCase(java.util.Locale.ROOT)),
                request.quantity(), request.limitPrice(), source == Order.Source.AI ? request.reason() : null,
                request.stockName(), request.reasoning(),
                request.holdingWeightLimitPercent(), request.isLowerTriggered(), source);
    }

    private Order reserveLimit(Long userId, long accountId, String stockCode, Order.Side side,
            long quantity, long limitPrice, String reason) {
        return reserveLimit(userId, accountId, stockCode, side, quantity, limitPrice, reason, null, java.util.List.of(), null, null, Order.Source.AI);
    }

    private Order reserveLimit(Long userId, long accountId, String stockCode, Order.Side side,
            long quantity, long limitPrice, String reason, String stockName, java.util.List<ReportReasoning> reasoning,
            Double holdingWeightLimitPercent, Boolean isLowerTriggered, Order.Source source) {
        if (side == null || quantity <= 0 || limitPrice <= 0 || stockCode == null
                || !stockCode.matches("[0-9]{6}")) {
            throw new OrderException("주문 입력값을 확인하세요.");
        }
        if (reason != null && (reason.isBlank() || reason.length() > 100000)) {
            throw new OrderException("AI 판단 근거는 공백이 아닌 100,000자 이하의 텍스트여야 합니다.");
        }
        Account account = lockedAccount(accountId);
        assertAccountSource(account, source);
        if (userId != null && !account.belongsTo(userId)) {
            throw new OrderException(HttpStatus.FORBIDDEN, "FORBIDDEN_ACCOUNT",
                    "이 계좌에 주문할 권한이 없습니다.");
        }
        long reserved;
        try {
            reserved = side == Order.Side.BUY ? Math.multiplyExact(quantity, limitPrice) : 0;
        } catch (ArithmeticException error) {
            throw new OrderException("주문금액이 허용 범위를 초과합니다.");
        }
        if (side == Order.Side.BUY && availableCash(account) < reserved) {
            throw new OrderException("주문 가능 현금이 부족합니다.");
        }
        if (side == Order.Side.SELL && sellableQuantity(accountId, stockCode) < quantity) {
            throw new OrderException("매도 가능 수량이 부족합니다.");
        }
        // pendingLimit()은 PENDING 객체를 만들고 Repository.save()가 DB 저장을 연결한다.
        // 여기의 orders는 OrderRepository이며 Controller의 orders(OrderService)와 타입이 다르다.
        // 예약금은 주문에 기록한다. 이 단계에서는 실제 현금이나 보유량을 차감하지 않는다.
        return orders.save(Order.pendingLimit(account, stockCode, side, quantity,
                limitPrice, source, reason, stockName, reasoning, holdingWeightLimitPercent, isLowerTriggered, clock.instant()));
    }

    @Transactional
    public Order cancel(long accountId, long orderId) {
        lockedAccount(accountId);
        return cancelPending(accountId, orderId);
    }

    @Transactional
    public Order cancel(long userId, long accountId, long orderId) {
        Account account = lockedAccount(accountId);
        if (!account.belongsTo(userId)) {
            throw new OrderException(HttpStatus.FORBIDDEN, "FORBIDDEN_ACCOUNT",
                    "이 계좌의 주문을 취소할 권한이 없습니다.");
        }
        return cancelPending(accountId, orderId);
    }

    private Order cancelPending(long accountId, long orderId) {
        Order order = orders.findByIdAndAccountId(orderId, accountId)
                .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND,
                        "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
        if (order.getStatus() != Order.Status.PENDING) {
            throw new OrderException(HttpStatus.CONFLICT, "ORDER_NOT_PENDING",
                    "대기 중인 주문만 취소할 수 있습니다.");
        }
        order.cancel(clock.instant());
        return order;
    }


    @Transactional(readOnly = true)
    public long availableCash(long accountId) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new OrderException("계좌를 찾을 수 없습니다."));
        return availableCash(account);
    }

    @Transactional(readOnly = true)
    public long sellableQuantity(long accountId, String stockCode) {
        long held = holdings.findByAccountIdAndStockCode(accountId, stockCode)
                .map(Holding::getQuantity).orElse(0L);
        long pending = orders.pendingSellQuantity(accountId, stockCode,
                Order.Side.SELL, Order.Status.PENDING);
        return Math.subtractExact(held, pending);
    }

    private Account lockedAccount(long accountId) {
        Account account = accounts.findLockedById(accountId)
                .orElseThrow(() -> new OrderException("계좌를 찾을 수 없습니다."));
        if (!account.isActive()) {
            throw new OrderException("활성 계좌가 아닙니다.");
        }
        assertCompetitionStarted(accountId);
        return account;
    }

    private void assertCompetitionStarted(long accountId) {
        // 계좌 ID로 직접 주문해도 시작 대기·종료된 대결의 운용을 우회할 수 없게 한다.
        if (accounts.hasNonTradingCompetition(accountId, clock.instant().atZone(SEOUL))) {
            throw new OrderException(HttpStatus.CONFLICT, "COMPETITION_NOT_IN_PROGRESS", "진행 중인 대결만 주문할 수 있습니다.");
        }
    }

    private void assertAccountSource(Account account, Order.Source source) {
        if (source == null || account.isAiManaged() != (source == Order.Source.AI)) {
            throw new OrderException(HttpStatus.FORBIDDEN, "FORBIDDEN_ACCOUNT",
                    "주문 주체와 계좌 운용 유형이 일치하지 않습니다.");
        }
    }

    private long availableCash(Account account) {
        return Math.subtractExact(account.getCashBalance(),
                orders.reservedCash(account.getId(), Order.Status.PENDING));
    }
}
