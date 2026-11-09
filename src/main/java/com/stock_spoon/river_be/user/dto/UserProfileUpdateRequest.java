package com.stock_spoon.river_be.user.dto;

import java.util.Set;
import tools.jackson.databind.JsonNode;
import com.stock_spoon.river_be.user.exception.ProfileUpdateException;

public record UserProfileUpdateRequest(boolean hasNickname, String nickname,
                                       boolean hasImage, String imageKey) {
    public static UserProfileUpdateRequest parse(JsonNode body) {
        if (body == null || !body.isObject() || body.isEmpty()
                || !Set.of("nickname", "profile_image_key").containsAll(body.propertyNames())) {
            throw ProfileUpdateException.invalid("수정할 닉네임 또는 이미지 키를 전달해주세요.");
        }
        String nickname = null;
        if (body.has("nickname")) {
            if (!body.get("nickname").isTextual()) throw ProfileUpdateException.invalid("닉네임은 문자열이어야 합니다.");
            nickname = body.get("nickname").stringValue().strip();
            if (nickname.isBlank() || nickname.length() > 50) {
                throw ProfileUpdateException.invalid("닉네임은 1~50자로 입력해주세요.");
            }
        }
        String key = null;
        if (body.has("profile_image_key") && !body.get("profile_image_key").isNull()) {
            if (!body.get("profile_image_key").isTextual()) throw ProfileUpdateException.invalidImage();
            key = body.get("profile_image_key").stringValue();
            if (key.isBlank() || key.length() > 255) throw ProfileUpdateException.invalidImage();
        }
        return new UserProfileUpdateRequest(body.has("nickname"), nickname, body.has("profile_image_key"), key);
    }
}
