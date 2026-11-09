package com.stock_spoon.river_be.competition.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "competition_judgment_summaries", indexes = {
        @Index(name = "ix_competition_judgment", columnList = "competition_id"),
        @Index(name = "ix_competition_judgment_participant", columnList = "competition_id,participant_id")
})
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
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private CompetitionParticipant participant;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    public CompetitionJudgmentSummary(CompetitionParticipant participant, String title, String content) {
        this.participant = participant;
        this.competition = participant.getCompetition();
        this.title = title;
        this.content = content;
    }
}
