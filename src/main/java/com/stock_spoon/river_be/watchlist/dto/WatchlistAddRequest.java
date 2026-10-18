package com.stock_spoon.river_be.watchlist.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.annotation.JsonDeserialize;

public record WatchlistAddRequest(
        @JsonProperty("stock_code") @NotBlank @Pattern(regexp = "[0-9A-Z]{6}")
        @JsonDeserialize(using = StrictStockCode.class) String stockCode) {

    public static class StrictStockCode extends tools.jackson.databind.ValueDeserializer<String> {
        @Override
        public String deserialize(tools.jackson.core.JsonParser parser,
                tools.jackson.databind.DeserializationContext context) throws tools.jackson.core.JacksonException {
            if (parser.currentToken() == JsonToken.VALUE_STRING) return parser.getString();
            return (String) context.handleUnexpectedToken(String.class, parser);
        }
    }
}