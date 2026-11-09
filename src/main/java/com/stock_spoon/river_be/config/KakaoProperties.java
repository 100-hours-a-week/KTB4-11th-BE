package com.stock_spoon.river_be.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kakao")
// [회원 탈퇴 수정] adminKey를 추가해 카카오 연결 해제용 서버 전용 Admin 키를 바인딩한다.
public record KakaoProperties(String clientId, String clientSecret, String redirectUri,
                              String frontendOrigin, boolean secureCookie, String adminKey) {
}
