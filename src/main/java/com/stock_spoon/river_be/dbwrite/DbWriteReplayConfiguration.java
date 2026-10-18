package com.stock_spoon.river_be.dbwrite;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class DbWriteReplayConfiguration {
    @Bean
    static BeanPostProcessor dbWriteReplayDataSourcePostProcessor(
            @Value("${db.write-replay.enabled:false}") boolean enabled,
            ObjectProvider<DbWriteReplayService> replayService) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!enabled || !"dataSource".equals(beanName) || !(bean instanceof DataSource dataSource)
                        || bean instanceof ProxyDataSource) {
                    return bean;
                }
                return ProxyDataSourceBuilder.create(dataSource)
                        .name("db-write-replay-dataSource")
                        .listener(replayService.getObject())
                        .build();
            }
        };
    }

    @Bean(destroyMethod = "close")
    SqsClient dbWriteReplaySqsClient(@Value("${db.write-replay.region:ap-northeast-2}") String region) {
        return SqsClient.builder().region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}
