package com.stock_spoon.river_be.market.kiwoom;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/** 실전 지수 조회를 명시적으로 활성화한 경우에만 실행한다. 인증값은 출력하지 않는다. */
@EnabledIfEnvironmentVariable(named = "KIWOOM_LIVE_TEST", matches = "true")
class KiwoomMarketLiveTests {
    @Test
    void retrievesKospiIndex() throws Exception {
        var local = new Properties();
        if (Files.exists(Path.of(".env.local"))) {
            try (var reader = Files.newBufferedReader(Path.of(".env.local"))) {
                local.load(reader);
            }
        }
        var config = new KiwoomConfig();
        var tokenProvider = config.kiwoomTokenProvider(
                setting("KIWOOM_APP_KEY", local), setting("KIWOOM_APP_SECRET", local));
        var index = config.kiwoomMarketClient(tokenProvider).kospi();
        assertThat(index.value()).isGreaterThan(BigDecimal.ZERO);
        assertThat(index.fetchedAt()).isNotNull();
    }

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
        var market = config.kiwoomMarketClient(tokenProvider);
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
        long price = config.kiwoomMarketClient(provider).currentPrice("005930");
        assertThat(price).isPositive();
        System.out.println("삼성전자 005930 REST 현재가 조회 성공: " + price + "원 (장중 실시간 여부는 검증하지 않음)");
    }

    private String setting(String name, Properties local) {
        String value = System.getenv(name);
        return value != null ? value : local.getProperty(name, "");
    }
}
