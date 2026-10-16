package com.stock_spoon.river_be.competition.entity;

import com.stock_spoon.river_be.account.entity.Account;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "competition_participants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompetitionParticipant {
    @Column(name = "final_asset_value")
    private Long finalAssetValue;

    @Column(name = "final_return_percent", precision = 19, scale = 4)
    private java.math.BigDecimal finalReturnPercent;

    public void recordFinalAssets(long assets) {
        if (assets < 0 || finalAssetValue != null) throw new IllegalStateException("최종 자산을 확정할 수 없습니다.");
        this.finalAssetValue = assets;
        this.finalReturnPercent = java.math.BigDecimal.valueOf(assets)
                .subtract(java.math.BigDecimal.valueOf(account.getInitialCapital()))
                .multiply(java.math.BigDecimal.valueOf(100))
                .divide(java.math.BigDecimal.valueOf(account.getInitialCapital()), 4, java.math.RoundingMode.HALF_UP);
    }
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "participant_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "competition_id", nullable = false)
    private Competition competition;

    // 계좌 하나는 참가 기록 하나에만 연결한다. 일반 계좌에는 이 참가 기록이 없다.
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    private Account account;

    public CompetitionParticipant(Competition competition, Account account) {
        this.competition = competition;
        this.account = account;
    }
}
