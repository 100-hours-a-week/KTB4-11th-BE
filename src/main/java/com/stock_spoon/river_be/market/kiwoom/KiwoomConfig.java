package com.stock_spoon.river_be.market.kiwoom;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class KiwoomConfig {
    @Bean
    KiwoomTokenProvider kiwoomTokenProvider(
            @Value("${kiwoom.app-key:}") String appKey,
            @Value("${kiwoom.app-secret:}") String appSecret) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        var client = RestClient.builder()
                .baseUrl("https://api.kiwoom.com")
                .requestFactory(factory).build();
        return new KiwoomTokenProvider(client, appKey, appSecret, Clock.systemUTC());
    }
}