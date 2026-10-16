package com.stock_spoon.river_be.competition.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "competition_daily_performances", uniqueConstraints =
        @UniqueConstraint(name = "uk_performance_participant_date", columnNames = {"participant_id", "record_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompetitionDailyPerformance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "performance_id")
    private Long id;

    // Java에서는 참가자 객체를 연결하고, DB에는 participant_id 외래 키로 저장한다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false, updatable = false)
    private CompetitionParticipant participant;

    @Column(name = "record_date", nullable = false, updatable = false)
    private LocalDate recordDate;

    @Column(name = "asset_value", nullable = false, updatable = false)
    private long assetValue;

    @Column(name = "cumulative_return_percent", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal cumulativeReturnPercent;

    // 날짜별 확정 기록은 수정 메서드를 제공하지 않는다. 다음 거래일에는 새로운 행을 추가한다.
    public CompetitionDailyPerformance(CompetitionParticipant participant, LocalDate recordDate, long assetValue) {
        this.participant = Objects.requireNonNull(participant);
        this.recordDate = Objects.requireNonNull(recordDate);
        if (assetValue < 0) throw new IllegalArgumentException("평가금은 음수일 수 없습니다.");
        this.assetValue = assetValue;
        long initialCapital = participant.getAccount().getInitialCapital();
        this.cumulativeReturnPercent = BigDecimal.valueOf(assetValue).subtract(BigDecimal.valueOf(initialCapital))
                .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(initialCapital), 4, RoundingMode.HALF_UP);
    }
}
