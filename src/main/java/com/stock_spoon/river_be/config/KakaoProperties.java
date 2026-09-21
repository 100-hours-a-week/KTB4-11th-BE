package com.stock_spoon.river_be.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kakao")
public record KakaoProperties(String clientId, String clientSecret, String redirectUri,
                              String frontendOrigin, boolean secureCookie) {
}
