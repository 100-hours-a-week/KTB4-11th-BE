package com.stock_spoon.river_be.config;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import io.lettuce.core.ClientOptions;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import static org.assertj.core.api.Assertions.*;

class RedisDeploymentConfigurationTests {
    private ApplicationContextRunner runner() throws Exception {
        var settings = new YamlPropertySourceLoader().load("redis-main",
                new FileSystemResource("src/main/resources/application.yaml")).getFirst();
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
                .withUserConfiguration(RedisDeploymentConfiguration.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addLast(settings));
    }

    @Test
    void contextsWithoutRedisTimeoutPropertiesStillStart() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
                .withUserConfiguration(RedisDeploymentConfiguration.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LettuceConnectionFactory.class).getClientConfiguration().getCommandTimeout())
                            .isEqualTo(Duration.ofSeconds(3));
                });
    }

    @Test
    void localDefaultsUseStandaloneWithoutAwsAndKeepTimeouts() throws Exception {
        runner().run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(AwsCredentialsProvider.class);
            var factory = context.getBean(LettuceConnectionFactory.class);
            assertThat(factory.getClusterConfiguration()).isNull();
            assertThat(factory.getStandaloneConfiguration().getPort()).isEqualTo(6380);
            assertThat(factory.getClientConfiguration().isUseSsl()).isFalse();
            assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofSeconds(3));
        });
    }

    @Test
    void infraVariablesUseTlsClusterAndRotatingIamCredentialsWithoutConnecting() throws Exception {
        runner().withPropertyValues("REDIS_CLUSTER_MODE=true", "REDIS_SSL=true", "REDIS_HOST=test.serverless.cache.amazonaws.com",
                "REDIS_PORT=6379", "REDIS_USERNAME=backend-user", "ELASTICACHE_CACHE_NAME=test-cache", "AWS_REGION=ap-northeast-2")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AwsCredentialsProvider.class);
                    var factory = context.getBean(LettuceConnectionFactory.class);
                    assertThat(factory.getClusterConfiguration().getClusterNodes()).hasSize(1);
                    assertThat(factory.getClusterConfiguration().getUsername()).isEqualTo("backend-user");
                    var config = factory.getClientConfiguration();
                    assertThat(config.isUseSsl()).isTrue();
                    assertThat(config.getClientOptions().orElseThrow().getReauthenticateBehaviour())
                            .isEqualTo(ClientOptions.ReauthenticateBehavior.ON_NEW_CREDENTIALS);
                    assertThat(config.getRedisCredentialsProviderFactory().orElseThrow()
                            .createCredentialsProvider(factory.getClusterConfiguration()).supportsStreaming()).isTrue();
                });
    }

    @Test
    void iamMisconfigurationFailsInsteadOfFallingBackToPassword() throws Exception {
        for (String missing : new String[]{"REDIS_SSL=false", "REDIS_USERNAME=", "AWS_REGION=", "REDIS_CLUSTER_MODE=false"}) {
            runner().withPropertyValues("REDIS_CLUSTER_MODE=true", "REDIS_SSL=true", "REDIS_USERNAME=backend-user",
                    "AWS_REGION=ap-northeast-2", "ELASTICACHE_CACHE_NAME=test-cache").withPropertyValues(missing)
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
