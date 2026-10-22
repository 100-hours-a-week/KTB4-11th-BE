package com.stock_spoon.river_be.market.kiwoom;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockMasterTests {
    @Test void directlyQueriesEvenWhenMetadataCacheExists() {
        var transport = mock(KiwoomRestTransport.class);
        var cache = mock(StockMetadataCache.class);
        when(transport.query("ka10100", "/api/dostk/stkinfo", Map.of("stk_cd", "005930")))
                .thenReturn(response());
        var result = new KiwoomMarketClient(transport, cache).stockMaster("005930");
        assertThat(result.stockCode()).isEqualTo("005930");
        assertThat(result.stockName()).isEqualTo("삼성전자");
        assertThat(result.marketCode()).isEqualTo("0");
        assertThat(result.sector()).isEqualTo("반도체");
        verify(transport).query("ka10100", "/api/dostk/stkinfo", Map.of("stk_cd", "005930"));
        verifyNoMoreInteractions(transport);
        verifyNoInteractions(cache);
    }

    @Test void missingOrBlankSectorIsNullable() {
        for (String value : new String[] {null, "  "}) {
            var raw = response();
            raw.put("upName", value);
            assertThat(client(raw).stockMaster("005930").sector()).isNull();
        }
    }

    @Test void rejectsMalformedOrProviderFailureWithoutTruncating() {
        for (var entry : Map.<String, Object>of("code", "000660", "name", " ",
                "marketCode", "x".repeat(11), "upName", 123, "return_code", 3).entrySet()) {
            var raw = response();
            raw.put(entry.getKey(), entry.getValue());
            assertThatThrownBy(() -> client(raw).stockMaster("005930")).isInstanceOf(IllegalStateException.class);
        }
        for (String field : new String[] {"name", "marketCode"}) {
            var raw = response(); raw.remove(field);
            assertThatThrownBy(() -> client(raw).stockMaster("005930")).isInstanceOf(IllegalStateException.class);
        }
        for (String field : new String[] {"name", "upName"}) {
            var raw = response(); raw.put(field, "x".repeat(101));
            assertThatThrownBy(() -> client(raw).stockMaster("005930")).isInstanceOf(IllegalStateException.class);
        }
    }

    private KiwoomMarketClient client(Map<String, Object> raw) {
        var transport = mock(KiwoomRestTransport.class);
        when(transport.query(anyString(), anyString(), anyMap())).thenReturn(raw);
        return new KiwoomMarketClient(transport);
    }
    private Map<String, Object> response() {
        return new HashMap<>(Map.of("return_code", 0, "code", "005930", "name", " 삼성전자 ",
                "marketCode", "0", "upName", " 반도체 "));
    }
}
