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
    KiwoomTokenProvider sharedKiwoomTokenProvider(
            @Value("${kiwoom.app-key:}") String appKey,
            @Value("${kiwoom.app-secret:}") String appSecret,
            org.springframework.data.redis.core.StringRedisTemplate redis,
            RedisCollectorHeartbeat heartbeat) {
        return new KiwoomTokenProvider(createClient(), appKey, appSecret, Clock.systemUTC(),
                new RedisKiwoomTokenStore(redis, appKey), heartbeat::ownsLease);
    }

    // 명시적으로 활성화한 단독 외부 API 테스트용. 운영 빈으로 등록하지 않는다.
    KiwoomTokenProvider kiwoomTokenProvider(String appKey, String appSecret) {
        return new KiwoomTokenProvider(createClient(), appKey, appSecret, Clock.systemUTC());
    }

    @Bean
    KiwoomRestTransport kiwoomRestTransport(KiwoomTokenProvider tokenProvider,
            RedisCollectorHeartbeat heartbeat,
            org.springframework.data.redis.core.StringRedisTemplate redis,
            @Value("${kiwoom.collector.enabled:false}") boolean collector,
            @Value("${kiwoom.collector.url:}") String url,
            @Value("${kiwoom.collector.secret:}") String secret) {
        if (collector) return new DirectKiwoomRestTransport(createClient(), tokenProvider, heartbeat::ownsLease);
        RestClient client = null;
        if (!url.isBlank()) {
            var uri = java.net.URI.create(url);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IllegalStateException("수집 담당 서버 주소 형식을 확인하세요.");
            }
            var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
            var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(Duration.ofSeconds(5));
            client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        }
        return new RemoteKiwoomRestTransport(client, redis, secret);
    }

    @Bean
    KiwoomMarketClient kiwoomMarketClient(KiwoomRestTransport transport, StockMetadataCache metadata) {
        return new KiwoomMarketClient(transport, metadata);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "kiwoom.stream.enabled", havingValue = "true")
    org.springframework.data.redis.listener.RedisMessageListenerContainer quoteListenerContainer(
            org.springframework.data.redis.connection.RedisConnectionFactory connections) {
        var container = new org.springframework.data.redis.listener.RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        return container;
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
