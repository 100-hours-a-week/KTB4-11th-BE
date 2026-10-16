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
