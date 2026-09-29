package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 내부 주문 예약·취소 규칙. HTTP 인증과 주문 생성 조건 검증은 컨트롤러에서 선행한다. */
@Service
public class OrderService {
    private final AccountRepository accounts;
    private final OrderRepository orders;
    private final HoldingRepository holdings;
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
        Account account = accounts.findByIdAndUserIdAndActiveTrue(accountId, userId)
                .orElseThrow(() -> new OrderException(HttpStatus.FORBIDDEN,
                        "FORBIDDEN_ACCOUNT", "이 계좌에 주문할 권한이 없습니다."));
        if (!account.isAiDelegated()) {
            throw new OrderException("운용 가능한 자동매매 계좌가 아닙니다.");
        }
    }

    @Transactional
    public Order reserveLimit(long accountId, String stockCode, Order.Side side,
            long quantity, long limitPrice, String decisionId, String decisionSummary) {
        return reserveLimit(null, accountId, stockCode, side, quantity, limitPrice,
                decisionId, decisionSummary);
    }

    @Transactional
    public Order reserveLimit(long userId, long accountId, String stockCode, Order.Side side,
            long quantity, long limitPrice, String decisionId, String decisionSummary) {
        return reserveLimit(Long.valueOf(userId), accountId, stockCode, side, quantity,
                limitPrice, decisionId, decisionSummary);
    }

    private Order reserveLimit(Long userId, long accountId, String stockCode, Order.Side side,
            long quantity, long limitPrice, String decisionId, String decisionSummary) {
        if (side == null || quantity <= 0 || limitPrice <= 0 || stockCode == null
                || !stockCode.matches("[0-9]{6}")) {
            throw new OrderException("주문 입력값을 확인하세요.");
        }
        if (decisionSummary != null && decisionSummary.length() > 500
                || decisionId != null && decisionId.length() > 100) {
            throw new OrderException("AI 판단 정보의 길이를 확인하세요.");
        }
        Account account = lockedAccount(accountId);
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
        return orders.save(Order.pendingLimit(account, stockCode, side, quantity,
                limitPrice, Order.Source.AI, decisionId, decisionSummary, clock.instant()));
    }

    @Transactional
    public Order cancel(long accountId, long orderId) {
        lockedAccount(accountId);
        Order order = orders.findByIdAndAccountId(orderId, accountId)
                .orElseThrow(() -> new OrderException("주문을 찾을 수 없습니다."));
        if (order.getStatus() != Order.Status.PENDING) {
            throw new OrderException("대기 중인 주문만 취소할 수 있습니다.");
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
        if (!account.isActive() || !account.isAiDelegated()) {
            throw new OrderException("운용 가능한 자동매매 계좌가 아닙니다.");
        }
        return account;
    }

    private long availableCash(Account account) {
        return Math.subtractExact(account.getCashBalance(),
                orders.reservedCash(account.getId(), Order.Status.PENDING));
    }
}
