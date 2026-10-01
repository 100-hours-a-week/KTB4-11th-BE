package com.stock_spoon.river_be.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record KakaoLoginResponse(String code, String message,
        @JsonProperty("user_id") @JsonInclude(JsonInclude.Include.NON_NULL) Long userId) {
    public static KakaoLoginResponse loginSuccess(long userId) {
        return new KakaoLoginResponse("LOGIN_SUCCESS", "로그인되었습니다.", userId);
    }

    public static KakaoLoginResponse tokenReissued() {
        return new KakaoLoginResponse("TOKEN_REISSUED", "토큰이 재발급되었습니다.", null);
    }

    public static KakaoLoginResponse logoutSuccess() {
        return new KakaoLoginResponse("LOGOUT_SUCCESS", "로그아웃되었습니다.", null);
    }
}
