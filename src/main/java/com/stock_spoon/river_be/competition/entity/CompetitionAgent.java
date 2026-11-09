package com.stock_spoon.river_be.competition.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "competition_agents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompetitionAgent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "agent_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false, unique = true)
    private CompetitionParticipant participant;

    @Column(name = "agent_position", nullable = false)
    private int position;

    @Column(name = "risk_level", nullable = false)
    private int riskLevel;

    @ElementCollection
    @CollectionTable(name = "competition_agent_preferred_themes", joinColumns = @JoinColumn(name = "agent_id"))
    @OrderColumn(name = "theme_position")
    @Column(name = "theme", nullable = false)
    private List<String> preferredThemes = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "competition_agent_excluded_themes", joinColumns = @JoinColumn(name = "agent_id"))
    @OrderColumn(name = "theme_position")
    @Column(name = "theme", nullable = false)
    private List<String> excludedThemes = new ArrayList<>();

    @Lob
    @Column(name = "additional_request", nullable = false, columnDefinition = "LONGTEXT")
    private String additionalRequest;

    public CompetitionAgent(CompetitionParticipant participant, int position, int riskLevel,
            List<String> preferredThemes, List<String> excludedThemes, String additionalRequest) {
        this.participant = participant;
        this.position = position;
        this.riskLevel = riskLevel;
        this.preferredThemes = new ArrayList<>(preferredThemes);
        this.excludedThemes = new ArrayList<>(excludedThemes);
        this.additionalRequest = additionalRequest;
    }
}
