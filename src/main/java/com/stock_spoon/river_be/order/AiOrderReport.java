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

    @Column(name = "stock_name", length = 255, updatable = false)
    private String stockName;
    @ElementCollection
    @CollectionTable(name = "ai_report_reasoning", joinColumns = @JoinColumn(name = "report_id"))
    @OrderColumn(name = "reasoning_index")
    private java.util.List<ReportReasoning> reasoning = new java.util.ArrayList<>();

    protected AiOrderReport() {}

    public AiOrderReport(Order order, String reason) {
        this(order, reason, null, java.util.List.of());
    }

    public AiOrderReport(Order order, String reason, String stockName, java.util.List<ReportReasoning> reasoning) {
        if (reason == null || reason.isBlank() || reason.length() > 100000) {
            throw new IllegalArgumentException("AI 판단 근거는 공백이 아닌 100,000자 이하의 텍스트여야 합니다.");
        }
        this.order = java.util.Objects.requireNonNull(order);
        this.reason = reason;
        if (stockName != null && (stockName.isBlank() || stockName.length() > 255)) {
            throw new IllegalArgumentException("종목명을 확인하세요.");
        }
        this.stockName = stockName;
        this.reasoning = new java.util.ArrayList<>(reasoning == null ? java.util.List.of() : java.util.List.copyOf(reasoning));
    }

    public Long getId() { return id; }
    public String getReason() { return reason; }
    public String getStockName() { return stockName; }
    public java.util.List<ReportReasoning> getReasoning() { return java.util.List.copyOf(reasoning); }
}
