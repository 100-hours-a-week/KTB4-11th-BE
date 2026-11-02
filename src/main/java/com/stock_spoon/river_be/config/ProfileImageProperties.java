package com.stock_spoon.river_be.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;

@Validated
@ConfigurationProperties("profile-image")
public record ProfileImageProperties(@DefaultValue("") String bucket,
        @DefaultValue("ap-northeast-2") String region,
        @DefaultValue("5242880") @Min(1) @Max(10485760) int maxBytes,
        @DefaultValue("300") @Min(30) @Max(3600) int uploadTtlSeconds,
        @DefaultValue("900") @Min(30) @Max(3600) int readTtlSeconds,
        @DefaultValue("20000000") @Min(1) @Max(20000000) long maxPixels) { }
