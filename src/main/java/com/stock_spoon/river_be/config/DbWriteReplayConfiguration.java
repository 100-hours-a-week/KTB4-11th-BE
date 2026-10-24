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
    @Bean(destroyMethod = "close")
    SqsClient dbWriteReplaySqsClient(DbWriteReplayProperties properties) {
        String queueUrl = properties.getQueueUrl();
        String queueName = queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
        boolean isDbWriteReplayQueue = queueName.equals("db-write-replay.fifo")
                || queueName.endsWith("-db-write-replay.fifo");
        if (queueUrl.isBlank() || !isDbWriteReplayQueue) {
            throw new IllegalStateException(
                    "DB_WRITE_REPLAY_QUEUE_URL must point to the DB migration db-write-replay FIFO queue");
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
