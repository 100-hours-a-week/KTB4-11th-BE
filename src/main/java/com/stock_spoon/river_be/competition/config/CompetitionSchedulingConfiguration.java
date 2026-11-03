package com.stock_spoon.river_be.competition.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "competition.completion.enabled", havingValue = "true", matchIfMissing = true)
public class CompetitionSchedulingConfiguration {
    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler competitionCompletionTaskScheduler() {
        // 종가 조회·과거 성과 복구가 공용 스케줄러의 주문 관련 작업을 지연시키지 않게 분리한다.
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("competition-completion-");
        return scheduler;
    }
}
