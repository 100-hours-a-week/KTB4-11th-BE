package com.stock_spoon.river_be.order;

import jakarta.persistence.*;

@Entity
@Table(name = "ai_order_reports")
public class AiOrderReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true, updatable = false)
    private Order order;
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT", updatable = false)
    private String reason;

    protected AiOrderReport() {}

    public AiOrderReport(Order order, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 100000) {
            throw new IllegalArgumentException("AI 판단 근거는 공백이 아닌 100,000자 이하의 텍스트여야 합니다.");
        }
        this.order = java.util.Objects.requireNonNull(order);
        this.reason = reason;
    }

    public Long getId() { return id; }
    public String getReason() { return reason; }
}
