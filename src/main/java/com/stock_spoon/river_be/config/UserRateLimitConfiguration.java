package com.stock_spoon.river_be.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisCredentialsProvider;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.resource.ClientResources;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.RedisCredentialsProviderFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import io.micrometer.core.instrument.MeterRegistry;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/** Creates the isolated Valkey connection only when per-user limiting is enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "security.user-rate-limit", name = "enabled", havingValue = "true")
class UserRateLimitConfiguration {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(UserRateLimitConfiguration.class);

    @Bean(name = "rateLimitRedisConnectionFactory")
    LettuceConnectionFactory rateLimitRedisConnectionFactory(
            DataRedisProperties properties,
            ClientResources resources,
            ObjectProvider<AwsCredentialsProvider> awsCredentials,
            @Value("${ELASTICACHE_CACHE_NAME:}") String cacheName,
            @Value("${RATE_LIMIT_REDIS_USERNAME:}") String username,
            @Value("${RATE_LIMIT_REDIS_PASSWORD:}") String password,
            @Value("${AWS_REGION:}") String region,
            @Value("${REDIS_CLUSTER_MODE:false}") boolean cluster) {
        ElastiCacheIamCredentials configuredIamCredentials = null;
        if (StringUtils.hasText(cacheName)) {
            if (!StringUtils.hasText(username) || !StringUtils.hasText(region)) {
                throw new IllegalStateException(
                        "Valkey rate-limit IAM 연결에는 RATE_LIMIT_REDIS_USERNAME과 AWS_REGION이 필요합니다.");
            }
            if (!cluster || !properties.getSsl().isEnabled()) {
                throw new IllegalStateException(
                        "Valkey rate-limit IAM 연결에는 REDIS_CLUSTER_MODE=true와 REDIS_SSL=true가 필요합니다.");
            }
            AwsCredentialsProvider aws = awsCredentials.getIfAvailable();
            if (aws == null) {
                throw new IllegalStateException("Valkey rate-limit IAM 연결에 AWS 자격 증명 공급자가 없습니다.");
            }
            configuredIamCredentials = new ElastiCacheIamCredentials(
                    aws, cacheName, username, region, Clock.systemUTC());
        }
        final ElastiCacheIamCredentials iamCredentials = configuredIamCredentials;

        ClientOptions.Builder options = cluster
                ? ClusterClientOptions.builder().topologyRefreshOptions(
                        ClusterTopologyRefreshOptions.builder().enablePeriodicRefresh(Duration.ofSeconds(60)).build())
                : ClientOptions.builder();
        options.socketOptions(SocketOptions.builder()
                        .connectTimeout(properties.getConnectTimeout() == null
                                ? Duration.ofSeconds(3) : properties.getConnectTimeout())
                        .build())
                .timeoutOptions(TimeoutOptions.enabled());
        if (iamCredentials != null) {
            options.reauthenticateBehavior(ClientOptions.ReauthenticateBehavior.ON_NEW_CREDENTIALS);
        }

        var client = LettuceClientConfiguration.builder()
                .clientResources(resources)
                .clientOptions(options.build())
                .commandTimeout(properties.getTimeout() == null ? Duration.ofSeconds(3) : properties.getTimeout());
        if (properties.getSsl().isEnabled()) client.useSsl();
        if (iamCredentials != null) {
            client.redisCredentialsProviderFactory(new RedisCredentialsProviderFactory() {
                @Override
                public RedisCredentialsProvider createCredentialsProvider(RedisConfiguration ignored) {
                    return iamCredentials;
                }
            });
        }

        if (cluster) {
            var configuration = new RedisClusterConfiguration();
            configuration.clusterNode(properties.getHost(), properties.getPort());
            if (StringUtils.hasText(username)) configuration.setUsername(username);
            if (iamCredentials == null && StringUtils.hasText(password)) configuration.setPassword(password);
            return new LettuceConnectionFactory(configuration, client.build());
        }

        var configuration = new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        configuration.setDatabase(properties.getDatabase());
        if (StringUtils.hasText(username)) configuration.setUsername(username);
        if (iamCredentials == null && StringUtils.hasText(password)) configuration.setPassword(password);
        return new LettuceConnectionFactory(configuration, client.build());
    }

    @Bean
    UserRateLimitService userRateLimitService(
            @Qualifier("rateLimitRedisConnectionFactory") RedisConnectionFactory connectionFactory,
            MeterRegistry meterRegistry,
            @Value("${security.user-rate-limit.max-requests:0}") int maxRequests,
            @Value("${security.user-rate-limit.window:60s}") Duration window) {
        if (maxRequests < 1) {
            throw new IllegalStateException("RATE_LIMIT_ENABLED=true이면 RATE_LIMIT_MAX_REQUESTS를 양수로 설정해야 합니다.");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalStateException("RATE_LIMIT_WINDOW은 0보다 큰 시간이어야 합니다.");
        }
        var template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        return new UserRateLimitService(template, maxRequests, window, meterRegistry);
    }
}
