package com.stock_spoon.river_be.market.kiwoom;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/** 실전 시세 조회를 명시적으로 활성화한 경우에만 실행한다. 인증값은 출력하지 않는다. */
@EnabledIfEnvironmentVariable(named = "KIWOOM_LIVE_TEST", matches = "true")
class KiwoomMarketLiveTests {
    @Test
    void checksStockInfoMarketAndStatusFields() throws Exception {
        var local = new Properties();
        if (Files.exists(Path.of(".env.local"))) {
            try (var reader = Files.newBufferedReader(Path.of(".env.local"))) {
                local.load(reader);
            }
        }
        var config = new KiwoomConfig();
        var tokenProvider = config.kiwoomTokenProvider(
                setting("KIWOOM_APP_KEY", local), setting("KIWOOM_APP_SECRET", local));
        var market = liveClient(config, tokenProvider);
        var samsung = market.stockInfo("005930");
        var etf = market.stockInfo("069500");
        assertThat(samsung.stockCode()).isEqualTo("005930");
        assertThat(etf.stockCode()).isEqualTo("069500");
        assertThat(samsung.marketCode()).isEqualTo("0");
        assertThat(etf.marketCode()).isEqualTo("8");
        assertThat(samsung.state()).isNotBlank();
        assertThat(etf.state()).isNotBlank();
    }

    @Test
    void retrievesSamsungCurrentPrice() throws Exception {
        var local = new Properties();
        if (Files.exists(Path.of(".env.local"))) {
            try (var reader = Files.newBufferedReader(Path.of(".env.local"))) {
                local.load(reader);
            }
        }
        var config = new KiwoomConfig();
        var provider = config.kiwoomTokenProvider(
                setting("KIWOOM_APP_KEY", local), setting("KIWOOM_APP_SECRET", local));
        long price = liveClient(config, provider).currentPrice("005930");
        assertThat(price).isPositive();
        System.out.println("삼성전자 005930 REST 현재가 조회 성공: " + price + "원 (장중 실시간 여부는 검증하지 않음)");
    }

    private KiwoomMarketClient liveClient(KiwoomConfig config, KiwoomTokenProvider provider) {
        // 명시적으로 켠 단독 외부 API 테스트는 운영 담당 임대와 분리한다.
        var heartbeat = org.mockito.Mockito.mock(RedisCollectorHeartbeat.class);
        org.mockito.Mockito.when(heartbeat.ownsLease()).thenReturn(true);
        return new KiwoomMarketClient(config.kiwoomRestTransport(provider, heartbeat, null, true, "", ""));
    }

    private String setting(String name, Properties local) {
        String value = System.getenv(name);
        return value != null ? value : local.getProperty(name, "");
    }
}
