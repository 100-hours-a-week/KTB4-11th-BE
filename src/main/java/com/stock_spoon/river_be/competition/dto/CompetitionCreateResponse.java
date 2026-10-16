package com.stock_spoon.river_be.competition.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter // Jackson이 응답 필드 값을 읽을 수 있도록 getter를 생성한다.
@AllArgsConstructor // 모든 필드 값을 받는 생성자를 만들어 응답 객체를 생성할 수 있게 한다.
public class CompetitionCreateResponse {
    private String message;
    @JsonProperty("competition_id") // Java 필드 competitionId를 JSON에서는 competition_id로 반환한다.
    private Long competitionId;
    private String state;
}
