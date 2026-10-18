package com.stock_spoon.river_be.watchlist;

import com.stock_spoon.river_be.watchlist.dto.WatchlistAddRequest;
import com.stock_spoon.river_be.watchlist.dto.WatchlistAddResponse;
import com.stock_spoon.river_be.watchlist.dto.WatchlistResponse;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WatchlistDtoTests {
    @Test
    void validatesStockCode() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String valid : List.of("005930", "000660", "12345A")) {
                assertTrue(validator.validate(new WatchlistAddRequest(valid)).isEmpty());
            }
            for (String invalid : List.of("", " ", "5930", "0059300", "12345a", "005930 ")) {
                assertFalse(validator.validate(new WatchlistAddRequest(invalid)).isEmpty());
            }
            assertFalse(validator.validate(new WatchlistAddRequest(null)).isEmpty());
        }
    }

    @Test
    void mapsRequestAndResponsesWithoutAccountOrPrices() {
        var mapper = JsonMapper.builder().build();
        var request = mapper.readValue("{\"stock_code\":\"005930\"}", WatchlistAddRequest.class);
        assertEquals("005930", request.stockCode());
        var added = mapper.readTree(mapper.writeValueAsString(new WatchlistAddResponse("success", "005930")));
        assertEquals("005930", added.get("stock_code").asText());
        assertEquals(2, added.size());
        var response = new WatchlistResponse("success", List.of(new WatchlistResponse.Item("005930", "삼성전자", "반도체")));
        var json = mapper.readTree(mapper.writeValueAsString(response));
        assertEquals(2, json.size());
        var item = json.get("watchlists").get(0);
        assertEquals("005930", item.get("stock_code").asText());
        assertEquals("삼성전자", item.get("stock_name").asText());
        assertEquals("반도체", item.get("sector").asText());
        assertEquals(3, item.size());
        var empty = mapper.readTree(mapper.writeValueAsString(new WatchlistResponse("success", List.of())));
        assertEquals(0, empty.get("watchlists").size());
    }
}