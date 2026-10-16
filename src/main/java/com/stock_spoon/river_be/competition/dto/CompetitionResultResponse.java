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
    @JsonProperty("competition_id")
    private Long competitionId;
    @JsonProperty("competition_name")
    private String competitionName;
    @JsonProperty("competition_type")
    private String competitionType;
    private String state;
    @JsonProperty("start_date")
    private LocalDate startDate;
    @JsonProperty("end_date")
    private LocalDate endDate;
    @JsonProperty("d_day")
    private Integer remainingDays;
    private String winner;
    @JsonProperty("user_final_return_percent")
    private BigDecimal userFinalReturnPercent;
    @JsonProperty("ai_final_return_percent")
    private BigDecimal aiFinalReturnPercent;
    private List<ParticipantItem> participants;
    @JsonProperty("daily_performances")
    private List<DailyPerformanceItem> dailyPerformances;

    @Getter
    @AllArgsConstructor
    public static class ParticipantItem {
        @JsonProperty("participant_id")
        private Long participantId;
        @JsonProperty("participant_type")
        private String participantType;
        @JsonProperty("account_id")
        private Long accountId;
        @JsonProperty("display_name")
        private String displayName;
    }

    @Getter
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.ALWAYS) // 누락된 기록은 0%와 구분할 수 있도록 null로 표현한다.
    public static class DailyPerformanceItem {
        private LocalDate date;
        @JsonProperty("user_cumulative_return_percent")
        private BigDecimal userCumulativeReturnPercent;
        @JsonProperty("ai_cumulative_return_percent")
        private BigDecimal aiCumulativeReturnPercent;
    }
}
