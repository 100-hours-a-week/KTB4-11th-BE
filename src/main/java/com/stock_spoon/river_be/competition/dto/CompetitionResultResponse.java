package com.stock_spoon.river_be.competition.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class CompetitionResultResponse {
    @JsonProperty("competition_id") private Long competitionId;
    @JsonProperty("competition_name") private String competitionName;
    @JsonProperty("competition_type") private String competitionType;
    private String state;
    @JsonProperty("start_date") private LocalDate startDate;
    @JsonProperty("end_date") private LocalDate endDate;
    @JsonProperty("d_day") private Integer remainingDays;
    private String winner;
    @JsonProperty("winner_participant_ids") private List<Long> winnerParticipantIds;
    private List<ParticipantItem> participants;

    @Getter
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class ParticipantItem {
        @JsonProperty("participant_id") private Long participantId;
        @JsonProperty("participant_type") private String participantType;
        @JsonProperty("account_id") private Long accountId;
        @JsonProperty("display_name") private String displayName;
        @JsonProperty("final_asset_value") private Long finalAssetValue;
        @JsonProperty("final_return_percent") private BigDecimal finalReturnPercent;
        private Integer rank;
        @JsonProperty("executed_trade_count") private long executedTradeCount;
        private CompetitionDetailResponse.Strategy strategy;
        @JsonProperty("daily_performances") private List<CompetitionDetailResponse.DailyPerformanceItem> dailyPerformances;
    }
}
