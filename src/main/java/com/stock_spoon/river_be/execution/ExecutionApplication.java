package com.stock_spoon.river_be.execution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/** BE 시작점과 분리하고, 공통 엔티티/저장소만 공유한다. */
@Configuration(proxyBeanMethods = false)
@Profile("execution")
@EnableAutoConfiguration
@EnableScheduling
@EntityScan("com.stock_spoon.river_be")
@EnableJpaRepositories("com.stock_spoon.river_be")
@ComponentScan(basePackages = {"com.stock_spoon.river_be.execution", "com.stock_spoon.river_be.order.service",
        "com.stock_spoon.river_be.order.validator", "com.stock_spoon.river_be.market"},
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = org.springframework.boot.context.TypeExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
                ".*KospiIndexController", ".*CollectorSecurityConfig", ".*CollectorQueryController"})})
@Import({com.stock_spoon.river_be.config.RedisDeploymentConfiguration.class,
        com.stock_spoon.river_be.order.exception.OrderExceptionHandler.class})
public class ExecutionApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(ExecutionApplication.class);
        app.setAdditionalProfiles("execution");
        app.run(args);
    }
}
