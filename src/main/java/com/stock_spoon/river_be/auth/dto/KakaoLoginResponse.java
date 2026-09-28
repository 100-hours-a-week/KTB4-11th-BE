package com.stock_spoon.river_be.auth.dto;

public record KakaoLoginResponse(String code, String message) {
    public static KakaoLoginResponse loginSuccess() {
        return new KakaoLoginResponse("LOGIN_SUCCESS", "로그인되었습니다.");
    }

    public static KakaoLoginResponse tokenReissued() {
        return new KakaoLoginResponse("TOKEN_REISSUED", "토큰이 재발급되었습니다.");
    }

    public static KakaoLoginResponse logoutSuccess() {
        return new KakaoLoginResponse("LOGOUT_SUCCESS", "로그아웃되었습니다.");
    }
}
