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
    private Integer remainingDays;
    private List<ParticipantItem> participants;

    @Getter
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class ParticipantItem {
        @JsonProperty("participant_id")
        private Long participantId;
        @JsonProperty("account_id")
        private Long accountId;
        @JsonProperty("participant_type")
        private String participantType;
        @JsonProperty("display_name")
        private String displayName;
        @JsonProperty("executed_trade_count")
        private long executedTradeCount;
        private Strategy strategy;
        @JsonProperty("top_return_holding")
        private TopReturnHolding topReturnHolding;
        @lombok.Setter @JsonProperty("total_assets")
        private Long totalAssets;
        @lombok.Setter @JsonProperty("cumulative_return_percent")
        private BigDecimal cumulativeReturnPercent;
        @lombok.Setter @JsonProperty("daily_performances")
        private List<DailyPerformanceItem> dailyPerformances;
        public ParticipantItem(Long participantId, Long accountId, String participantType, String displayName,
                long executedTradeCount, Strategy strategy, TopReturnHolding topReturnHolding) {
            this.participantId = participantId; this.accountId = accountId; this.participantType = participantType;
            this.displayName = displayName; this.executedTradeCount = executedTradeCount;
            this.strategy = strategy; this.topReturnHolding = topReturnHolding;
        }


    }

    public record Strategy(
            @JsonProperty("risk_level") int riskLevel,
            @JsonProperty("preferred_themes") List<String> preferredThemes,
            @JsonProperty("excluded_themes") List<String> excludedThemes,
            @JsonProperty("additional_request") String additionalRequest) {}

    public record TopReturnHolding(
            @JsonProperty("stock_code") String stockCode,
            @JsonProperty("stock_name") String stockName,
            @JsonProperty("return_percent") BigDecimal returnPercent) {
        public TopReturnHolding { returnPercent = returnPercent.setScale(1, java.math.RoundingMode.HALF_UP); }
    }

    @Getter
    public static class DailyPerformanceItem {
        private LocalDate date;
        @JsonProperty("cumulative_return_percent")
        private BigDecimal cumulativeReturnPercent;
        public DailyPerformanceItem(LocalDate date, BigDecimal cumulativeReturnPercent) {
            this.date = date;
            this.cumulativeReturnPercent = cumulativeReturnPercent.setScale(1, java.math.RoundingMode.HALF_UP);
        }
    }
}
