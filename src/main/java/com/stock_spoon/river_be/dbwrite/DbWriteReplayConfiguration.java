package com.stock_spoon.river_be.dbwrite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class DbWriteReplayConfiguration {
    @Bean(destroyMethod = "close")
    SqsClient dbWriteReplaySqsClient(@Value("${db.write-replay.region:ap-northeast-2}") String region) {
        return SqsClient.builder().region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}
