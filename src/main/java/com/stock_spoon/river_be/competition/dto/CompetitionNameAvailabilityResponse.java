package com.stock_spoon.river_be.competition.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter // 각 필드의 값을 읽는 메서드를 만들어 JSON 응답에 사용한다.
@AllArgsConstructor // 사용 가능 여부를 받아 응답 객체를 만든다.
public class CompetitionNameAvailabilityResponse {
    private boolean available; // true면 현재 이름을 사용할 수 있다.
}
