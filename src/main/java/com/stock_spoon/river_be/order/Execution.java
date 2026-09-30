package com.stock_spoon.river_be.order;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/** 체결 이력. 생성 후 수정하는 도메인 메서드를 제공하지 않는다. */
@Entity
@Table(name = "executions", indexes = @Index(name = "ix_executions_order", columnList = "order_id"))
public class Execution {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "execution_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;
    @Column(name = "execution_price", nullable = false, updatable = false)
    private long price;
    @Column(name = "execution_quantity", nullable = false, updatable = false)
    private long quantity;
    @Column(name = "realized_pnl", precision = 19, scale = 2, updatable = false)
    private BigDecimal realizedPnl;
    @Column(name = "realized_return_percent", precision = 19, scale = 4, updatable = false)
    private BigDecimal realizedReturnPercent;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Execution() {}

    public Execution(Order order, long price, long quantity, BigDecimal realizedPnl,
            BigDecimal realizedReturnPercent, Instant createdAt) {
        this.order = Objects.requireNonNull(order);
        this.createdAt = Objects.requireNonNull(createdAt);
        if (price <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("체결 가격과 수량은 양수여야 합니다.");
        }
        if (order.getSide() == Order.Side.BUY && (realizedPnl != null || realizedReturnPercent != null)) {
            throw new IllegalArgumentException("매수 체결에는 실현손익이 없습니다.");
        }
        this.price = price;
        this.quantity = quantity;
        this.realizedPnl = realizedPnl == null ? null : realizedPnl.setScale(2, RoundingMode.HALF_UP);
        this.realizedReturnPercent = realizedReturnPercent == null ? null
                : realizedReturnPercent.setScale(4, RoundingMode.HALF_UP);
    }

    public Long getId() { return id; }
    public Long getOrderId() { return order.getId(); }
    public long getPrice() { return price; }
    public long getQuantity() { return quantity; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public BigDecimal getRealizedReturnPercent() { return realizedReturnPercent; }
    public Instant getCreatedAt() { return createdAt; }
}