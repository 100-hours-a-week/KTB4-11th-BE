package com.stock_spoon.river_be.market.kiwoom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 명시적으로 허용한 경우에만 실전 서버에서 토큰을 발급한다. 토큰은 출력하지 않는다. */
@EnabledIfEnvironmentVariable(named = "KIWOOM_LIVE_TEST", matches = "true")
class KiwoomTokenLiveTests {
    @Test
    void issuesAndReusesProductionToken() throws Exception {
        var local = new Properties();
        var path = Path.of(".env.local");
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path)) {
                local.load(reader);
            }
        }
        var provider = new KiwoomConfig().kiwoomTokenProvider(
                setting("KIWOOM_APP_KEY", local), setting("KIWOOM_APP_SECRET", local));
        String token = provider.accessToken();
        assertTrue(token != null && !token.isBlank(), "토큰 발급 확인");
        assertTrue(token.equals(provider.accessToken()), "발급한 토큰 재사용 확인");
    }

    private String setting(String name, Properties local) {
        String value = System.getenv(name);
        return value != null ? value : local.getProperty(name, "");
    }
}