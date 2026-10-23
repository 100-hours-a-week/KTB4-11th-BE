package com.stock_spoon.river_be.order.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "order_request_records", indexes = @Index(name = "ix_order_request_account", columnList = "account_id"))
public class OrderRequestRecord {
    @Id @Column(name = "event_id", length = 128) private String eventId;
    @Column(name = "account_id", nullable = false) private long accountId;
    @Column(name = "user_id", nullable = false) private long userId;
    @Column(name = "payload_hash", nullable = false, length = 64) private String payloadHash;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "order_id") private Long orderId;
    @Column(name = "reason_code", length = 64) private String reasonCode;
    @Column(name = "processed_at", nullable = false) private Instant processedAt;
    protected OrderRequestRecord() {}
    public OrderRequestRecord(String eventId, long accountId, long userId, String hash, Long orderId, String reasonCode, Instant now) {
        this.eventId = eventId; this.accountId = accountId; this.userId = userId; this.payloadHash = hash;
        this.orderId = orderId; this.reasonCode = reasonCode; this.processedAt = now;
        this.status = orderId == null ? "REJECTED" : "CREATED";
    }
    public String eventId() { return eventId; }
    public long accountId() { return accountId; }
    public long userId() { return userId; }
    public String payloadHash() { return payloadHash; }
    public String status() { return status; }
    public Long orderId() { return orderId; }
    public String reasonCode() { return reasonCode; }
    public Instant processedAt() { return processedAt; }
}
