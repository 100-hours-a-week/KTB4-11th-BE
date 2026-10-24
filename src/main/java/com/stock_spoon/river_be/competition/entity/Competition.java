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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Competition.class);
    public enum Type { USER_VS_AI }
    public enum State { READY, IN_PROGRESS, COMPLETED, CANCELLED }
    public enum Winner { USER, AI, DRAW }

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Winner winner;

    @Column(name = "completed_at")
    private Instant completedAt;

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
            log.warn("event=competition_transition_rejected stage=start_state_validation competitionId={} state={} code=COMPETITION_NOT_READY", id, state);
            throw new IllegalStateException("시작 대기 중인 대결만 시작할 수 있습니다.");
        }
        this.startedAt = java.util.Objects.requireNonNull(startedAt);
        this.state = State.IN_PROGRESS;
    }

    // 공휴일은 아직 고려하지 않는다. 주말 종료일은 직전 금요일 종가로 평가한다.
    public LocalDate tradingEndDate() {
        LocalDate date = endDate;
        while (date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) date = date.minusDays(1);
        return date;
    }

    public Instant tradingEndsAt() {
        return tradingEndDate().atTime(15, 30).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant();
    }

    public boolean isTradingAt(java.time.ZonedDateTime now) {
        return state == State.IN_PROGRESS && !now.toLocalDate().isBefore(startDate)
                && now.toInstant().isBefore(tradingEndsAt());
    }

    public void complete(Winner winner, Instant completedAt) {
        if (state != State.IN_PROGRESS || completedAt.isBefore(tradingEndsAt())) {
            log.warn("event=competition_transition_rejected stage=completion_state_and_time_validation competitionId={} state={} code=INVALID_COMPLETION_STATE_OR_TIME", id, state);
            throw new IllegalStateException("종료 시각이 지난 진행 중 대결만 완료할 수 있습니다.");
        }
        this.winner = java.util.Objects.requireNonNull(winner);
        this.completedAt = completedAt;
        this.state = State.COMPLETED;
    }
}
