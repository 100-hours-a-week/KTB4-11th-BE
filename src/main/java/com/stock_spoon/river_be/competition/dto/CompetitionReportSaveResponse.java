package com.stock_spoon.river_be.competition.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter // Jackson이 응답 객체의 필드 값을 읽을 수 있도록 getter를 만든다.
@AllArgsConstructor // 대결 ID와 메시지를 받아 응답 객체를 만드는 생성자를 만든다.
public class CompetitionReportSaveResponse {
    @JsonProperty("competition_id") // Java의 competitionId를 JSON의 competition_id로 반환한다.
    private Long competitionId;
    private String message;
}
