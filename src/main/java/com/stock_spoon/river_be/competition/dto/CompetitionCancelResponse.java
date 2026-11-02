package com.stock_spoon.river_be.competition.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter // 응답을 JSON으로 변환할 때 필드 값을 읽도록 getter를 만든다.
@AllArgsConstructor // 대결 ID와 변경된 상태를 받는 생성자를 만든다.
public class CompetitionCancelResponse {
    @JsonProperty("competition_id") // JSON에서는 snake_case 이름으로 반환한다.
    private Long competitionId;
    private String state;
}
