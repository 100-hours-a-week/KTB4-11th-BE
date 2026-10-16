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
@JsonInclude(JsonInclude.Include.ALWAYS) // 완료·취소 상태의 d_day도 생략하지 않고 null로 반환한다.
public class CompetitionDetailResponse {
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
    private Integer dDay;
    @JsonProperty("cumulative_return_percent")
    private BigDecimal cumulativeReturnPercent;
    private List<ParticipantItem> participants;
    @JsonProperty("daily_performances")
    private List<DailyPerformanceItem> dailyPerformances;

    @Getter
    @AllArgsConstructor
    public static class ParticipantItem {
        @JsonProperty("participant_id")
        private Long participantId;
        @JsonProperty("account_id")
        private Long accountId;
        @JsonProperty("participant_type")
        private String participantType;
        @JsonProperty("display_name")
        private String displayName;
    }

    @Getter
    @AllArgsConstructor
    public static class DailyPerformanceItem {
        private LocalDate date;
        @JsonProperty("cumulative_return_percent")
        private BigDecimal cumulativeReturnPercent;
    }
}
