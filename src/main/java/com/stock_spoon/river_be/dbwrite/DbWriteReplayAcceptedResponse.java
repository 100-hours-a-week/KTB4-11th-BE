package com.stock_spoon.river_be.dbwrite;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DbWriteReplayAcceptedResponse(
        String status,
        @JsonProperty("request_id") String requestId,
        String message) {
    public static DbWriteReplayAcceptedResponse queued(String requestId) {
        return queued(requestId, "DB 쓰기 전환이 끝난 뒤 재처리하도록 요청을 보관했습니다.");
    }

    public static DbWriteReplayAcceptedResponse queued(String requestId, String message) {
        return new DbWriteReplayAcceptedResponse("queued", requestId, message);
    }
}
