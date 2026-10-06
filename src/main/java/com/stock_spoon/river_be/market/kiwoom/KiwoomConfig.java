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
        return new KiwoomTokenProvider(createClient(), appKey, appSecret, Clock.systemUTC());
    }

    @Bean
    KiwoomMarketClient kiwoomMarketClient(KiwoomTokenProvider tokenProvider) {
        return new KiwoomMarketClient(createClient(), tokenProvider);
    }

    @Bean
    KiwoomStockStream kiwoomStockStream(KiwoomTokenProvider tokenProvider,
            @Value("${kiwoom.stream.enabled:false}") boolean enabled,
            org.springframework.context.ApplicationEventPublisher events) {
        var stream = new KiwoomStockStream(tokenProvider, enabled, java.util.List.of());
        // 현재가 수신을 Spring 이벤트 발행에 연결한다.
        // KiwoomStockStream → publishEvent(StockPrice) → OrderExecutionListener.onPrice() 순서다.
        stream.setPriceListener(events::publishEvent);
        return stream;
    }

    private RestClient createClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        var client = RestClient.builder()
                .baseUrl("https://api.kiwoom.com")
                .bufferContent((uri, method) -> true)
                .requestFactory(factory).build();
        return client;
    }
}
