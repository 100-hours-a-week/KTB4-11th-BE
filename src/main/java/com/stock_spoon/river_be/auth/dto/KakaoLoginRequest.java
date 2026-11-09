package com.stock_spoon.river_be.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// FE에서 OAuth state 검증을 마친 뒤 인가코드만 전송합니다.
public record KakaoLoginRequest(
        @io.swagger.v3.oas.annotations.media.Schema(description = "프론트에서 OAuth state 검증을 마친 카카오 일회용 인가 코드", example = "카카오에서_발급받은_인가_코드")
        @JsonProperty("authorization_code") @NotBlank @Size(max = 2048) String authorizationCode) {
    @Override
    public String toString() {
        return "KakaoLoginRequest[credentials=REDACTED]";
    }
}
