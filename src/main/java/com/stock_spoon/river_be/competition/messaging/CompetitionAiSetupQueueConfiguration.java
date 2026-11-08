package com.stock_spoon.river_be.competition.messaging;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "competition.ai-setup.queue.enabled", havingValue = "true")
public class CompetitionAiSetupQueueConfiguration {
    // SQS 응답 대기가 기존 대결·주문 예약 작업을 막지 않도록 전용 스레드 한 개를 사용한다.
    @Bean(destroyMethod = "shutdown")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler competitionAiSetupTaskScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("competition-ai-setup-");
        return scheduler;
    }

    @Bean(destroyMethod = "close")
    SqsAsyncClient competitionAiSetupSqsClient(@Value("${competition.ai-setup.queue.region:ap-northeast-2}") String region,
            @Value("${competition.ai-setup.queue.url}") String url) {
        if (!url.startsWith("https://sqs." + region + ".amazonaws.com/") || url.endsWith(".fifo")) {
            throw new IllegalStateException("AI_REQUEST_QUEUE_URL은 해당 리전의 SQS Standard Queue URL이어야 합니다.");
        }
        // 기본 자격 증명 체인으로 EC2 IAM Role을 사용한다. Access/Secret Key를 코드에 넣지 않는다.
        return SqsAsyncClient.builder().region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(5))).build();
    }

    @Bean
    CompetitionAiSetupRequestPublisher competitionAiSetupRequestPublisher(
            @Qualifier("competitionAiSetupSqsClient") SqsAsyncClient sqs,
            @Value("${competition.ai-setup.queue.url}") String url) {
        return new CompetitionAiSetupRequestPublisher(sqs, url);
    }
}
