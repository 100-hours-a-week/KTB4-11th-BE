package com.stock_spoon.river_be.market.kiwoom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/** LOGIN/REG 실전 검증. 장외에는 REAL이 없을 수 있으므로 가격 수신 성공으로 간주하지 않는다. */
@EnabledIfEnvironmentVariable(named = "KIWOOM_STREAM_LIVE_TEST", matches = "true")
class KiwoomStockStreamLiveTests {
    @Test
    void authenticatesAndSubscribesSamsung() throws Exception {
        var local = new Properties();
        if (Files.exists(Path.of(".env.local"))) {
            try (var reader = Files.newBufferedReader(Path.of(".env.local"))) {
                local.load(reader);
            }
        }
        var config = new KiwoomConfig();
        var tokens = config.kiwoomTokenProvider(setting("KIWOOM_APP_KEY", local),
                setting("KIWOOM_APP_SECRET", local));
        tokens.accessToken(); // 연결 전 인증 실패 원인을 분리해 확인한다.
        var stream = new KiwoomStockStream(tokens, true, List.of("005930"));
        try {
            stream.maintainConnection();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (stream.state() != KiwoomStockStream.State.SUBSCRIBED
                    && stream.state() != KiwoomStockStream.State.DISCONNECTED
                    && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertThat(stream.state()).as("키움 실전 LOGIN/REG 결과")
                    .isEqualTo(KiwoomStockStream.State.SUBSCRIBED);
            System.out.println("삼성전자 005930: LOGIN/REG 성공. REAL 수신 여부: "
                    + stream.latest("005930").isPresent());
        } finally {
            stream.close();
        }
    }

    private String setting(String name, Properties local) {
        String value = System.getenv(name);
        return value != null ? value : local.getProperty(name, "");
    }
}


