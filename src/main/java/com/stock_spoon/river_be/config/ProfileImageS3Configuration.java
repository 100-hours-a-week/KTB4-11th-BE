package com.stock_spoon.river_be.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

@Configuration
@EnableConfigurationProperties(ProfileImageProperties.class)
public class ProfileImageS3Configuration {
    @Bean
    S3Client profileImageS3Client(ProfileImageProperties properties) {
        return S3Client.builder().region(Region.of(properties.region()))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15))
                        .apiCallAttemptTimeout(Duration.ofSeconds(8)))
                .build();
    }

    @Bean
    S3Presigner profileImageS3Presigner(ProfileImageProperties properties) {
        return S3Presigner.builder().region(Region.of(properties.region())).build();
    }
}
