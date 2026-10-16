package com.stock_spoon.river_be.competition.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "competition_market_analyses", indexes = @Index(name = "ix_competition_analysis_date", columnList = "competition_id,analysis_date"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompetitionMarketAnalysis {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "competition_id", nullable = false)
    private Competition competition;
    @Column(name = "analysis_date", nullable = false)
    private LocalDate date;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    public CompetitionMarketAnalysis(Competition competition, LocalDate date, String title, String content) {
        this.competition = competition;
        this.date = date;
        this.title = title;
        this.content = content;
    }
}
