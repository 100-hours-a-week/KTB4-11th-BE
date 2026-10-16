package com.stock_spoon.river_be.competition.entity;

import com.stock_spoon.river_be.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity // 이 클래스의 객체를 DB 테이블의 행으로 저장한다.
@Table(name = "competitions", indexes = @Index(name = "ix_competitions_state_dates", columnList = "state,start_date,end_date"))
@Getter // 값을 읽는 메서드만 생성한다. 상태 변경은 아래 업무 메서드로 제한한다.
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA가 객체를 복원할 때 사용할 기본 생성자다.
public class Competition {
    public enum Type { USER_VS_AI }
    public enum State { READY, IN_PROGRESS, COMPLETED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // DB가 발급한 자동 증가 ID를 사용한다.
    @Column(name = "competition_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false) // 한 사용자가 여러 대결을 만들 수 있다.
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "competition_name", nullable = false, length = 20)
    private String competitionName;

    @Enumerated(EnumType.STRING) // 순번 대신 USER_VS_AI 같은 이름으로 저장한다.
    @Column(name = "competition_type", nullable = false, length = 20)
    private Type competitionType;

    @Column(name = "initial_capital", nullable = false)
    private long initialCapital;

    @Column(name = "ai_participant_count", nullable = false)
    private int aiParticipantCount;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private State state;

    // 요청의 시작 날짜와 실제 거래를 시작한 시각은 다를 수 있다.
    @Column(name = "started_at")
    private Instant startedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Competition(User user, String competitionName, Type competitionType,
            LocalDate startDate, LocalDate endDate, long initialCapital, int aiParticipantCount) {
        this.user = user;
        this.competitionName = competitionName;
        this.competitionType = competitionType;
        this.startDate = startDate;
        this.endDate = endDate;
        this.initialCapital = initialCapital;
        this.aiParticipantCount = aiParticipantCount;
        this.state = State.READY;
    }

    public void start(Instant startedAt) {
        if (state != State.READY) {
            throw new IllegalStateException("시작 대기 중인 대결만 시작할 수 있습니다.");
        }
        this.startedAt = java.util.Objects.requireNonNull(startedAt);
        this.state = State.IN_PROGRESS;
    }
}
