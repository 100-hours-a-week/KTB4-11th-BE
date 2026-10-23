package com.stock_spoon.river_be.config;

import java.util.List;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "db-write-replay", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DbWriteReplayProperties.class)
public class DbWriteReplayConfiguration {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DbWriteReplayConfiguration.class);
    @Bean(destroyMethod = "close")
    SqsClient dbWriteReplaySqsClient(DbWriteReplayProperties properties) {
        if (properties.getQueueUrl().isBlank() || !properties.getQueueUrl().endsWith(".fifo")) {
            log.error("event=db_write_replay_configuration_failed stage=sqs_client code=FIFO_QUEUE_URL_REQUIRED");
            throw new IllegalStateException(
                    "DB_WRITE_REPLAY_QUEUE_URL must point to an SQS FIFO queue when replay is enabled");
        }
        return SqsClient.builder().build();
    }

    @Bean
    DbWriteReplayQueryListener dbWriteReplayQueryListener(
            DbWriteReplayRequestContext requestContext) {
        return new DbWriteReplayQueryListener(requestContext);
    }

    @Bean
    DbWriteReplayExceptionResolver dbWriteReplayExceptionResolver(
            DbWriteReplayProperties properties,
            DbWriteReplayRequestContext requestContext,
            SqsClient sqsClient,
            JsonMapper objectMapper) {
        return new DbWriteReplayExceptionResolver(properties, requestContext, sqsClient, objectMapper);
    }

    @Bean
    WebMvcConfigurer dbWriteReplayExceptionResolverConfigurer(
            DbWriteReplayExceptionResolver dbWriteReplayExceptionResolver) {
        return new WebMvcConfigurer() {
            @Override
            public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
                resolvers.add(0, dbWriteReplayExceptionResolver);
            }
        };
    }

    @Bean
    static BeanPostProcessor dbWriteReplayDataSourceProxy(DbWriteReplayQueryListener queryListener) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof DataSource dataSource) || bean instanceof ProxyDataSource) {
                    return bean;
                }
                ProxyDataSource proxy = ProxyDataSourceBuilder.create(dataSource)
                        .name("db-write-replay-" + beanName)
                        .build();
                proxy.addListener(queryListener);
                return proxy;
            }
        };
    }
}
