package com.stock_spoon.river_be.competition.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter // 각 필드를 읽는 getXxx() 메서드를 생성한다.
@NoArgsConstructor // Jackson이 요청 DTO를 생성할 때 사용할 기본 생성자를 만든다.
public class CompetitionCreateRequest {
    // @JsonProperty는 JSON의 snake_case 이름과 Java의 camelCase 필드를 연결한다.
    @JsonProperty("competition_name")
    @NotBlank
    @Size(max = 20) // 대결명을 계좌명으로 사용하므로 기존 계좌와 같은 길이 제한을 적용한다.
    private String competitionName;

    @JsonProperty("competition_type")
    @NotBlank
    private String competitionType;

    @JsonProperty("start_date")
    @NotNull
    private LocalDate startDate;

    @JsonProperty("end_date")
    @NotNull
    private LocalDate endDate;

    @JsonProperty("initial_capital")
    @NotNull
    @Min(1000000)
    @Max(100000000)
    private Long initialCapital;

    @JsonProperty("ai_participant_count")
    @Min(1)
    private Integer aiParticipantCount;
}
