package com.stock_spoon.river_be.holding.entity;

import com.stock_spoon.river_be.account.entity.Account;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "holdings", uniqueConstraints = @UniqueConstraint(name = "uk_holdings_account_stock", columnNames = {"account_id", "stock_code"}))
public class Holding {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Holding.class);
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "holding_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;
    @Column(name = "stock_code", nullable = false, length = 6)
    private String stockCode;
    @Column(nullable = false)
    private long quantity;
    @Column(name = "total_cost", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalCost;

    protected Holding() {}

    public Holding(Account account, String stockCode, long quantity, BigDecimal totalCost) {
        if (quantity <= 0 || totalCost == null || totalCost.setScale(2, java.math.RoundingMode.HALF_UP).signum() <= 0) {
            log.warn("event=holding_mutation_rejected stage=creation_input_validation code=INVALID_INITIAL_QUANTITY_OR_COST");
            throw new IllegalArgumentException("보유수량과 취득원가를 확인하세요.");
        }
        this.account = account;
        this.stockCode = stockCode;
        this.quantity = quantity;
        this.totalCost = totalCost.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public void add(long boughtQuantity, BigDecimal cost) {
        if (boughtQuantity <= 0 || cost == null || cost.signum() <= 0) {
            log.warn("event=holding_mutation_rejected stage=buy_input_validation holdingId={} code=INVALID_BOUGHT_QUANTITY_OR_COST", id);
            throw new IllegalArgumentException("매수 수량과 원가를 확인하세요.");
        }
        quantity = Math.addExact(quantity, boughtQuantity);
        totalCost = totalCost.add(cost).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public void reduce(long soldQuantity, BigDecimal soldCost) {
        if (soldQuantity <= 0 || soldQuantity >= quantity || soldCost == null || soldCost.signum() <= 0
                || soldCost.compareTo(totalCost) > 0) {
            log.warn("event=holding_mutation_rejected stage=sell_input_validation holdingId={} code=INVALID_SOLD_QUANTITY_OR_COST", id);
            throw new IllegalArgumentException("매도 수량과 원가를 확인하세요.");
        }
        var remainingCost = totalCost.subtract(soldCost).setScale(2, java.math.RoundingMode.HALF_UP);
        if (remainingCost.signum() <= 0) {
            log.warn("event=holding_mutation_rejected stage=remaining_cost_validation holdingId={} code=NON_POSITIVE_REMAINING_COST", id);
            throw new IllegalArgumentException("남은 취득원가는 양수여야 합니다.");
        }
        quantity -= soldQuantity;
        totalCost = remainingCost;
    }

    public long getQuantity() { return quantity; }
    public BigDecimal getTotalCost() { return totalCost; }
    public Long getAccountId() { return account.getId(); }
    public String getStockCode() { return stockCode; }
}
