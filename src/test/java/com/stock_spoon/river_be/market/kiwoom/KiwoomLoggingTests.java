package com.stock_spoon.river_be.market.kiwoom;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KiwoomLoggingTests {
    @Test
    void logsHttpAndProviderCodesWithoutBodiesOrCredentials() {
        var builder = RestClient.builder().baseUrl("https://api.kiwoom.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        var tokens = mock(KiwoomTokenProvider.class);
        when(tokens.accessToken()).thenReturn("secret-token");
        var client = new KiwoomMarketClient(builder.build(), tokens);
        Logger logger = (Logger) LoggerFactory.getLogger(KiwoomMarketClient.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            server.expect(requestTo("https://api.kiwoom.com/api/dostk/sect"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("secret-body"));
            server.expect(requestTo("https://api.kiwoom.com/api/dostk/sect"))
                    .andRespond(withSuccess("{\"return_code\":3,\"return_msg\":\"[8005:secret-token]\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://api.kiwoom.com/api/dostk/sect"))
                    .andRespond(withSuccess("{\"return_code\":3,\"return_msg\":\"[8005:secret-token]\"}", MediaType.APPLICATION_JSON));
            assertThatThrownBy(client::kospi).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(client::kospi).isInstanceOf(IllegalStateException.class);
            assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("event=kiwoom_query_failed", "apiId=ka20001", "httpStatus=503", "elapsedMs="));
            assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("event=kiwoom_query_rejected", "returnCode=3", "detailCode=8005"));
            assertThat(logs.list.toString()).doesNotContain("secret-token", "secret-body");
            assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("event=kiwoom_query_auth_retry", "apiId=ka20001"));
            verify(tokens).invalidate("secret-token");
            server.verify();
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
