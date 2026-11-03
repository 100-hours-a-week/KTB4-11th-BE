package com.stock_spoon.river_be.competition.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "competition_report_outbox", indexes = {
        @Index(name = "idx_report_outbox_status_id", columnList = "status,outbox_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_report_outbox_competition", columnNames = "competition_id"),
        @UniqueConstraint(name = "uk_report_outbox_request", columnNames = "request_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompetitionReportOutbox {
    public enum Status { PENDING, SENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "competition_id", nullable = false, updatable = false)
    private Competition competition;

    // 동일 요청을 재전송해도 식별자가 바뀌지 않도록 저장한다.
    @Column(name = "request_id", nullable = false, updatable = false, length = 36)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CompetitionReportOutbox(Competition competition, String requestId) {
        this.competition = java.util.Objects.requireNonNull(competition);
        if (requestId == null || requestId.isBlank() || requestId.length() > 36) {
            throw new IllegalArgumentException("요청 식별자를 확인해주세요.");
        }
        this.requestId = requestId;
        this.status = Status.PENDING;
    }

    // SQS 전송 성공을 확인한 뒤 호출한다. AI 리포트 생성 완료를 의미하지 않는다.
    public void markSent() {
        this.status = Status.SENT;
    }
}
