package com.stock_spoon.river_be.market;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class MarketHttpConfig {
    @Bean
    MarketStockClient marketStockClient(
            @Value("${market.http.base-url:https://stock-spoon.com/market}") String baseUrl) {
        var uri = URI.create(baseUrl);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("시세 서버 주소 형식을 확인하세요.");
        }
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        var client = RestClient.builder().baseUrl(baseUrl.replaceAll("/+$", ""))
                .requestFactory(factory).build();
        return new MarketStockClient(client);
    }
}
