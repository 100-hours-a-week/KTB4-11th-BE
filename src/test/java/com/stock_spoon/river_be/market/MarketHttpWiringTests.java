package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.market.kiwoom.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketHttpWiringTests {
    @Configuration(proxyBeanMethods = false)
    @Import({KiwoomConfig.class, MarketHttpConfig.class, RedisCollectorHeartbeat.class,
            CollectorQueryController.class, CollectorSecurityConfig.class})
    static class WiringConfiguration {}

    @Test void staleCollectorEnvironmentDoesNotActivateLegacyInfrastructure() {
        new ApplicationContextRunner().withUserConfiguration(WiringConfiguration.class)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(StockMetadataCache.class, () -> mock(StockMetadataCache.class))
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.SystemEnvironmentPropertySource("obsoleteCollectorEnv", java.util.Map.of(
                    "KIWOOM_COLLECTOR_ENABLED", "true", "KIWOOM_COLLECTOR_URL", "invalid-old-url"))))
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(KiwoomMarketClient.class)
                    .doesNotHaveBean(KiwoomRestTransport.class).doesNotHaveBean(RedisCollectorHeartbeat.class)
                    .doesNotHaveBean(CollectorQueryController.class).doesNotHaveBean(CollectorSecurityConfig.class);
            });
    }
    @Test void productionStartsWithoutLegacyTransportEvenWithObsoleteCollectorUrl() {
        new ApplicationContextRunner().withUserConfiguration(KiwoomConfig.class, MarketHttpConfig.class)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(RedisCollectorHeartbeat.class, () -> mock(RedisCollectorHeartbeat.class))
            .withBean(StockMetadataCache.class, () -> mock(StockMetadataCache.class))
            .withPropertyValues("kiwoom.collector.url=obsolete-invalid-url", "kiwoom.collector.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(KiwoomMarketClient.class)
                    .hasSingleBean(MarketStockClient.class).doesNotHaveBean(KiwoomTokenProvider.class)
                    .doesNotHaveBean(KiwoomRestTransport.class);
            });
    }
}
