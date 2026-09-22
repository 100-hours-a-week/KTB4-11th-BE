package com.stock_spoon.river_be.auth.dto;

public record KakaoLoginResponse(String code, String message) {
    public static KakaoLoginResponse verified() {
        return new KakaoLoginResponse("KAKAO_AUTH_VERIFIED", "카카오 인증이 확인되었습니다.");
    }
}
