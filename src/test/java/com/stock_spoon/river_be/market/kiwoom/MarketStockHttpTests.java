package com.stock_spoon.river_be.market.kiwoom;

import com.stock_spoon.river_be.market.MarketStockClient;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MarketStockHttpTests {
    private MockRestServiceServer server;
    private KiwoomRestTransport legacy;
    private KiwoomMarketClient market;
    private MarketStockClient stocks;
    private static final String RESPONSE = """
        {"code":"005930","name":"삼성전자","market":"KOSPI","sector_name":"전기전자",
         "english_name":null,"corp_code":"00126380","synced_at":"2026-11-03T09:00:00+09:00",
         "themes":[{"code":"550","is_major":true,"name":"반도체_생산"}]}
        """;

    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("https://market.example/market");
        server = MockRestServiceServer.bindTo(builder).build();
        legacy = mock(KiwoomRestTransport.class);
        stocks = new MarketStockClient(builder.build());
        market = new KiwoomConfig().kiwoomMarketClient(legacy, null, stocks);
    }

    private void respond(String json) {
        server.expect(requestTo("https://market.example/market/stocks/005930"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(headerDoesNotExist("Authorization"))
            .andExpect(headerDoesNotExist("api-id"))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test void masterUsesMarketHttpAndPreservesStoredMarketCode() {
        respond(RESPONSE);
        assertThat(market.stockMaster("005930"))
            .isEqualTo(new KiwoomMarketClient.StockMaster("005930", "삼성전자", "0", "전기전자"));
        server.verify();
        verifyNoInteractions(legacy);
    }

    @Test void detailsExtractOnlyRequiredFieldsAndAllowEmptySector() {
        respond(RESPONSE.replace("전기전자", ""));
        assertThat(market.stockDetails("005930"))
            .isEqualTo(new KiwoomMarketClient.StockDetails("005930", "삼성전자", ""));
        server.verify();
        verifyNoInteractions(legacy);
    }

    @Test void metadataMissLoadsMarketHttpInsteadOfCollector() {
        var cache = mock(StockMetadataCache.class);
        when(cache.load(eq("005930"), any())).thenAnswer(call ->
            ((java.util.function.Supplier<?>) call.getArgument(1)).get());
        market = new KiwoomMarketClient(legacy, cache, stocks);
        respond(RESPONSE);
        assertThat(market.stockDetails("005930").stockName()).isEqualTo("삼성전자");
        server.verify();
        verifyNoInteractions(legacy);
    }

    @Test void cachedMetadataAvoidsAllExternalRequests() {
        var cache = mock(StockMetadataCache.class);
        when(cache.load(eq("005930"), any())).thenReturn(Map.of("code", "005930", "name", "캐시", "upName", ""));
        market = new KiwoomMarketClient(legacy, cache, stocks);
        assertThat(market.stockDetails("005930").stockName()).isEqualTo("캐시");
        server.verify();
        verifyNoInteractions(legacy);
    }

    @Test void malformedStockNeverFallsBackToKiwoom() {
        respond(RESPONSE.replace("005930", "000660"));
        assertThatThrownBy(() -> market.stockMaster("005930")).isInstanceOf(IllegalStateException.class);
        server.verify();
        verifyNoInteractions(legacy);
    }

    @Test void unknownMarketNeverBecomesAnInventedDatabaseCode() {
        respond(RESPONSE.replace("KOSPI", "UNKNOWN"));
        assertThatThrownBy(() -> market.stockMaster("005930")).isInstanceOf(IllegalStateException.class);
        server.verify();
        verifyNoInteractions(legacy);
    }

    @Test void providerFailureIsSanitizedAndNeverFallsBackToKiwoom() {
        server.expect(requestTo("https://market.example/market/stocks/005930"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("private provider detail"));
        assertThatThrownBy(() -> market.stockDetails("005930"))
            .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("private provider");
        server.verify();
        verifyNoInteractions(legacy);
    }

    @Test void invalidCodeDoesNotSendRequest() {
        assertThatThrownBy(() -> market.stockMaster("../oops")).isInstanceOf(IllegalArgumentException.class);
        server.verify();
        verifyNoInteractions(legacy);
    }

    private void bars(String suffix, String json) {
        server.expect(requestTo("https://market.example/market/bars/005930?timeframe=1d&limit=1000" + suffix))
            .andExpect(method(HttpMethod.GET)).andExpect(headerDoesNotExist("Authorization"))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test void closingPricePagesUntilExactUtcDailyDateWithoutKiwoom() {
        bars("", """
            {"items":[{"timestamp":"2026-11-03T00:00:00Z","close":82000}],"next_cursor":"next"}
            """);
        bars("&cursor=next", """
            {"items":[{"timestamp":"2026-11-02T00:00:00Z","close":81000.0}],"next_cursor":null}
            """);
        assertThat(market.closingPrice("005930", java.time.LocalDate.of(2026,11,2))).isEqualTo(81000);
        server.verify();
        verifyNoInteractions(legacy);
    }

    @Test void closingPriceDoesNotSubstituteOtherDateOrRoundFractionalPrices() {
        for (String response : new String[] {
            "{\"items\":[{\"timestamp\":\"2026-11-01T00:00:00Z\",\"close\":80000}],\"next_cursor\":null}",
            "{\"items\":[{\"timestamp\":\"2026-11-02T00:00:00Z\",\"close\":80000.5}],\"next_cursor\":null}",
            "{\"items\":[{\"timestamp\":\"2026-11-02T00:00:00Z\",\"close\":-10}],\"next_cursor\":null}",
            "{\"items\":[],\"next_cursor\":null}"}) {
            setup(); bars("", response);
            assertThatThrownBy(() -> market.closingPrice("005930", java.time.LocalDate.of(2026,11,2)))
                .isInstanceOf(IllegalStateException.class);
            server.verify(); verifyNoInteractions(legacy);
        }
    }

    @Test void closingPriceRejectsRepeatedCursorAndProviderErrors() {
        bars("", "{\"items\":[],\"next_cursor\":\"same\"}");
        bars("&cursor=same", "{\"items\":[],\"next_cursor\":\"same\"}");
        assertThatThrownBy(() -> market.closingPrice("005930", java.time.LocalDate.of(2026,11,2)))
            .isInstanceOf(IllegalStateException.class);
        server.verify(); verifyNoInteractions(legacy);
        setup();
        server.expect(anything()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> market.closingPrice("005930", java.time.LocalDate.of(2026,11,2)))
            .isInstanceOf(IllegalStateException.class);
        server.verify(); verifyNoInteractions(legacy);
    }

    @Test void collectorStockQueryAlsoUsesMarketHttp() {
        var heartbeat = mock(RedisCollectorHeartbeat.class);
        when(heartbeat.ownsLease()).thenReturn(true);
        when(heartbeat.ownerId()).thenReturn("owner");
        var controller = new CollectorQueryController(legacy, heartbeat, market);
        respond(RESPONSE);
        assertThat(controller.query(new CollectorQuery("ka10100", "005930", "owner")))
            .containsEntry("name", "삼성전자").containsEntry("marketCode", "0");
        server.verify();
        verifyNoInteractions(legacy);
    }

    @Test void rejectsMissingOrWrongTypeFieldsAndEmptyBody() {
        for (String response : new String[] {"null", "{}", RESPONSE.replace("\"name\":\"삼성전자\"", "\"name\":17"),
                RESPONSE.replace("\"sector_name\":\"전기전자\"", "\"sector_name\":null"),
                RESPONSE.replace("삼성전자", " "), RESPONSE.replace("삼성전자", "x".repeat(101))}) {
            setup();
            respond(response);
            assertThatThrownBy(() -> market.stockDetails("005930")).isInstanceOf(IllegalStateException.class);
            server.verify();
            verifyNoInteractions(legacy);
        }
    }
}
