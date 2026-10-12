package com.stock_spoon.river_be.config;

import io.lettuce.core.*;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.resource.ClientResources;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.*;

/** 인프라 환경변수로 연결 방식만 선택한다. 데이터 저장·담당 정책은 변경하지 않는다. */
@Configuration(proxyBeanMethods = false)
public class RedisDeploymentConfiguration {
    @Bean
    LettuceConnectionFactory redisConnectionFactory(DataRedisProperties properties, ClientResources resources,
            ObjectProvider<ElastiCacheIamCredentials> credentials, @Value("${REDIS_CLUSTER_MODE:false}") boolean cluster) {
        var iam = credentials.getIfAvailable();
        if (iam != null && (!cluster || !properties.getSsl().isEnabled())) {
            throw new IllegalStateException("Serverless IAM 연결에는 REDIS_CLUSTER_MODE=true와 REDIS_SSL=true가 필요합니다.");
        }
        ClientOptions.Builder options = cluster ? ClusterClientOptions.builder().topologyRefreshOptions(
                ClusterTopologyRefreshOptions.builder().enablePeriodicRefresh(Duration.ofSeconds(60)).build()) : ClientOptions.builder();
        options.socketOptions(SocketOptions.builder().connectTimeout(properties.getConnectTimeout() == null
                        ? Duration.ofSeconds(3) : properties.getConnectTimeout()).build())
                .timeoutOptions(TimeoutOptions.enabled());
        if (iam != null) options.reauthenticateBehavior(ClientOptions.ReauthenticateBehavior.ON_NEW_CREDENTIALS);
        var client = LettuceClientConfiguration.builder().clientResources(resources).clientOptions(options.build())
                .commandTimeout(properties.getTimeout() == null ? Duration.ofSeconds(3) : properties.getTimeout());
        if (properties.getSsl().isEnabled()) client.useSsl();
        if (iam != null) client.redisCredentialsProviderFactory(new RedisCredentialsProviderFactory() {
            public RedisCredentialsProvider createCredentialsProvider(RedisConfiguration configuration) { return iam; }
        });
        if (cluster) {
            var configuration = new RedisClusterConfiguration();
            configuration.clusterNode(properties.getHost(), properties.getPort());
            configuration.setUsername(properties.getUsername());
            if (iam == null && StringUtils.hasText(properties.getPassword())) configuration.setPassword(properties.getPassword());
            return new LettuceConnectionFactory(configuration, client.build());
        }
        var configuration = new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        configuration.setDatabase(properties.getDatabase());
        configuration.setUsername(properties.getUsername());
        if (StringUtils.hasText(properties.getPassword())) configuration.setPassword(properties.getPassword());
        return new LettuceConnectionFactory(configuration, client.build());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${ELASTICACHE_CACHE_NAME:}')")
    static class IamConfiguration {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(AwsCredentialsProvider.class)
        DefaultCredentialsProvider redisAwsCredentialsProvider() { return DefaultCredentialsProvider.builder().build(); }

        @Bean
        ElastiCacheIamCredentials redisIamCredentials(AwsCredentialsProvider aws, DataRedisProperties properties,
                @Value("${ELASTICACHE_CACHE_NAME}") String cache, @Value("${AWS_REGION:}") String region) {
            if (!StringUtils.hasText(properties.getUsername()) || !StringUtils.hasText(region)) {
                throw new IllegalStateException("IAM 연결에는 REDIS_USERNAME과 AWS_REGION이 필요합니다.");
            }
            return new ElastiCacheIamCredentials(aws, cache, properties.getUsername(), region, Clock.systemUTC());
        }
    }
}
