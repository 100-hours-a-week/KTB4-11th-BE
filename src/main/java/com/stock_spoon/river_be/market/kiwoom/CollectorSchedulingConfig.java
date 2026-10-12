package com.stock_spoon.river_be.market.kiwoom;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class CollectorSchedulingConfig {
    @Bean
    ThreadPoolTaskScheduler taskScheduler() { return scheduler("be-scheduled-"); }

    @Bean
    ThreadPoolTaskScheduler collectorLeaseScheduler() { return scheduler("collector-lease-"); }

    private ThreadPoolTaskScheduler scheduler(String name) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(name);
        return scheduler;
    }
}
