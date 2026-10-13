package com.stock_spoon.river_be.execution.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** 완료한 내부 시장가 요청만 자산 변경과 함께 커밋한다. */
@Entity
@Table(name = "execution_requests")
public class ExecutionRequest {
    @Id @Column(name = "request_id", length = 36, nullable = false, updatable = false)
    private String requestId;
    @Column(name = "user_id", nullable = false, updatable = false) private long userId;
    @Column(name = "account_id", nullable = false, updatable = false) private long accountId;
    @Column(name = "payload_hash", length = 64, nullable = false, updatable = false) private String payloadHash;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true, updatable = false)
    private com.stock_spoon.river_be.order.entity.Order order;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    protected ExecutionRequest() {}
    public ExecutionRequest(String id, long userId, long accountId, String hash,
            com.stock_spoon.river_be.order.entity.Order order) {
        this.requestId = id; this.userId = userId; this.accountId = accountId;
        this.payloadHash = hash; this.order = order; this.createdAt = Instant.now();
    }
    public boolean matches(long user, long account, String hash) {
        return userId == user && accountId == account && payloadHash.equals(hash);
    }
    public long orderId() { return order.getId(); }
}
