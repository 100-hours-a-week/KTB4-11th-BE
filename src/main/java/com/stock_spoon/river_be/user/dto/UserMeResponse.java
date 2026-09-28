package com.stock_spoon.river_be.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UserMeResponse(
        String nickname,
        @JsonProperty("profile_image_url") String profileImageUrl) {
}
