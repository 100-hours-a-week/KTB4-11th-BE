package com.stock_spoon.river_be.competition.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

// AI 서버가 보낸 JSON을 Java 객체로 변환할 때 사용할 요청 DTO다.
@Getter
@NoArgsConstructor
public class CompetitionReportSaveRequest {
    // JSON의 news와 thoughts 배열을 각각 목록으로 받는다.
    @NotNull // 배열은 필수지만 빈 배열은 허용한다.
    @Valid // 배열 안에 있는 News 객체의 검증 조건도 검사한다.
    private List<@NotNull News> news; // 배열의 각 원소가 null이 되는 것을 막는다.

    @NotNull
    @Valid // 배열 안에 있는 Thought 객체의 검증 조건도 검사한다.
    private List<@NotNull Thought> thoughts;

    @Getter
    @NoArgsConstructor
    public static class News {
        @NotBlank // null, 빈 문자열, 공백만 있는 문자열을 허용하지 않는다.
        @Size(max = 255) // 기존 DB의 제목 VARCHAR(255) 제한에 맞춘다.
        private String title;
        @NotBlank
        private String summary;
    }

    @Getter
    @NoArgsConstructor
    public static class Thought {
        @NotBlank
        @Size(max = 255)
        private String title;
        @NotBlank
        private String text;
    }
}
