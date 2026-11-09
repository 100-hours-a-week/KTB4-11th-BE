package com.stock_spoon.river_be.user.exception;

import org.springframework.http.HttpStatus;

public class ProfileUpdateException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ProfileUpdateException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static ProfileUpdateException invalid(String message) {
        return new ProfileUpdateException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    public static ProfileUpdateException invalidImage() {
        return new ProfileUpdateException(HttpStatus.BAD_REQUEST, "INVALID_PROFILE_IMAGE", "프로필 이미지 키와 업로드 파일을 확인해주세요.");
    }

    public static ProfileUpdateException unavailable() {
        return new ProfileUpdateException(HttpStatus.SERVICE_UNAVAILABLE, "PROFILE_IMAGE_UNAVAILABLE", "이미지 저장소를 사용할 수 없습니다.");
    }
}
