package com.stock_spoon.river_be.competition.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@AllArgsConstructor
public class CompetitionListResponse {
    @JsonProperty("ongoing_competition_count")
    private long ongoingCompetitionCount;
    private List<CompetitionItem> competitions;

    @Getter
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.ALWAYS) // 미확정 결과도 필드를 생략하지 않고 null로 반환한다.
    public static class CompetitionItem {
        @JsonProperty("competition_id")
        private Long competitionId;

        private String state;

        @JsonProperty("competition_name")
        private String competitionName;

        @JsonProperty("competition_type")
        private String competitionType;

        @JsonProperty("start_date")
        private LocalDate startDate;

        @JsonProperty("end_date")
        private LocalDate endDate;

        // 완료된 대결은 null을 반환하므로 기본형 int 대신 Integer 사용
        @JsonProperty("d_day")
        private Integer dDay;

        @JsonProperty("cumulative_return_percent")
        private Double cumulativeReturnPercent;

        private String winner;
    }


}
