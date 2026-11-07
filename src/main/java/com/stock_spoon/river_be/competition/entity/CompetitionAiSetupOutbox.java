package com.stock_spoon.river_be.competition.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "competition_ai_setup_outbox", indexes = {
        @Index(name = "idx_ai_setup_outbox_status_id", columnList = "status,outbox_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_ai_setup_outbox_competition", columnNames = "competition_id"),
        @UniqueConstraint(name = "uk_ai_setup_outbox_request", columnNames = "event_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompetitionAiSetupOutbox {
    public enum Status { PENDING, SENT, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "competition_id", nullable = false, updatable = false)
    private Competition competition;

    // 동일 요청을 재전송해도 식별자가 바뀌지 않도록 저장한다.
    @Column(name = "event_id", nullable = false, updatable = false, length = 36)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT", updatable = false)
    private String payload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CompetitionAiSetupOutbox(Competition competition, String eventId, String payload) {
        this.competition = java.util.Objects.requireNonNull(competition);
        if (eventId == null || eventId.isBlank() || eventId.length() > 36) {
            throw new IllegalArgumentException("요청 식별자를 확인해주세요.");
        }
        this.eventId = eventId;
        this.payload = java.util.Objects.requireNonNull(payload);
        this.status = Status.PENDING;
    }

    // 전송 전에 취소된 대결은 이후 재시도 대상에서 제외한다.
    public void markCancelled() { this.status = Status.CANCELLED; }

    // SQS 전달 완료이며 AI 설정 완료를 뜻하지 않는다.
    public void markSent() {
        this.status = Status.SENT;
    }
}
