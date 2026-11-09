package com.stock_spoon.river_be.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProfileImageUploadResponse(@JsonProperty("presigned_url") String presignedUrl,
                                        @JsonProperty("profile_image_key") String profileImageKey) { }
