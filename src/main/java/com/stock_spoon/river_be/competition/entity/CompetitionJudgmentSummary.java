package com.stock_spoon.river_be.competition.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "competition_judgment_summaries", indexes = @Index(name = "ix_competition_judgment", columnList = "competition_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompetitionJudgmentSummary {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "summary_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "competition_id", nullable = false)
    private Competition competition;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    public CompetitionJudgmentSummary(Competition competition, String title, String content) {
        this.competition = competition;
        this.title = title;
        this.content = content;
    }
}
