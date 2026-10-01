package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.account.entity.Account;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "trade_orders", indexes = @Index(name = "ix_orders_account_status", columnList = "account_id,status"))
public class Order {
    public enum Side { BUY, SELL }
    public enum Type { MARKET, LIMIT }
    public enum Status { PENDING, EXECUTED, CANCELLED }
    public enum Source { AI, USER }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;
    @Column(name = "stock_code", nullable = false, length = 6)
    private String stockCode;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    private Side side;
    @Enumerated(EnumType.STRING) @Column(name = "order_type", nullable = false, length = 8)
    private Type type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private Status status;
    @Enumerated(EnumType.STRING) @Column(name = "order_source", nullable = false, length = 8)
    private Source source;
    @Column(nullable = false)
    private long quantity;
    @Column(name = "limit_price")
    private Long limitPrice;
    @Column(name = "reserved_cash", nullable = false)
    private long reservedCash;
    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private AiOrderReport report;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected Order() {}

    public static Order pendingLimit(Account account, String stockCode, Side side,
            long quantity, long limitPrice, Source source, String reason, Instant now) {
        var order = new Order();
        order.account = account;
        order.stockCode = stockCode;
        order.side = side;
        order.type = Type.LIMIT;
        order.status = Status.PENDING;
        order.source = source;
        order.quantity = quantity;
        order.limitPrice = limitPrice;
        order.reservedCash = side == Side.BUY ? Math.multiplyExact(quantity, limitPrice) : 0;
        if (reason != null) order.report = new AiOrderReport(order, reason);
        order.createdAt = now;
        return order;
    }

    public static Order pendingMarketBuy(Account account, String stockCode, long quantity,
            String reason, Instant now) {
        var order = new Order();
        order.account = account;
        order.stockCode = stockCode;
        order.side = Side.BUY;
        order.type = Type.MARKET;
        order.status = Status.PENDING;
        order.source = Source.AI;
        order.quantity = quantity;
        order.report = new AiOrderReport(order, reason);
        order.createdAt = now;
        return order;
    }

    public void execute() {
        if (status != Status.PENDING) throw new IllegalStateException("대기 주문만 체결할 수 있습니다.");
        status = Status.EXECUTED;
        reservedCash = 0;
    }

    public void cancel(Instant now) {
        if (status != Status.PENDING) throw new IllegalStateException("대기 중인 주문만 취소할 수 있습니다.");
        status = Status.CANCELLED;
        reservedCash = 0;
        cancelledAt = now;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return account.getId(); }
    public String getStockCode() { return stockCode; }
    public Side getSide() { return side; }
    public Type getType() { return type; }
    public Status getStatus() { return status; }
    public Source getSource() { return source; }
    public long getQuantity() { return quantity; }
    public Long getLimitPrice() { return limitPrice; }
    public long getReservedCash() { return reservedCash; }
    public AiOrderReport getReport() { return report; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCancelledAt() { return cancelledAt; }
}
