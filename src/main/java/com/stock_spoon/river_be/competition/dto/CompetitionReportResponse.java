package com.stock_spoon.river_be.competition.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CompetitionReportResponse {
    @JsonProperty("competition_id")
    private Long competitionId;
    @JsonProperty("competition_name")
    private String competitionName;
    @JsonProperty("start_date")
    private LocalDate startDate;
    @JsonProperty("end_date")
    private LocalDate endDate;
    private String state;
    @JsonProperty("market_analyses")
    private List<MarketAnalysisItem> marketAnalyses;
    @JsonProperty("judgment_summaries")
    private List<JudgmentSummaryItem> judgmentSummaries;

    @Getter
    @AllArgsConstructor
    public static class MarketAnalysisItem {
        @JsonProperty("analysis_id")
        private Long analysisId;
        private LocalDate date;
        private String title;
        private String content;
    }

    @Getter
    @AllArgsConstructor
    public static class JudgmentSummaryItem {
        private String title;
        private String content;
    }
}
