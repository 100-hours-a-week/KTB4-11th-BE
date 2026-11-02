package com.stock_spoon.river_be.user.dto;

import java.util.Set;
import tools.jackson.databind.JsonNode;
import com.stock_spoon.river_be.user.exception.ProfileUpdateException;

public record ProfileImageUploadRequest(String contentType, long contentLength) {
    public static ProfileImageUploadRequest parse(JsonNode body) {
        if (body == null || !body.isObject()
                || !body.propertyNames().equals(Set.of("content_type", "content_length"))
                || !body.get("content_type").isTextual()
                || !body.get("content_length").isIntegralNumber()
                || !body.get("content_length").canConvertToLong()) {
            throw ProfileUpdateException.invalid("content_type과 바이트 단위 content_length를 전달해주세요.");
        }
        return new ProfileImageUploadRequest(body.get("content_type").stringValue(), body.get("content_length").longValue());
    }
}
