package com.stock_spoon.river_be.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.stock_spoon.river_be.config.DbReadRetryable;
import com.stock_spoon.river_be.user.dto.AiUserSnapshotResponse;
import com.stock_spoon.river_be.user.service.AiUserSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AI 서버 전용")
@RestController
public class AiUserSnapshotController {
    private final AiUserSnapshotService service;

    public AiUserSnapshotController(AiUserSnapshotService service) {
        this.service = service;
    }

    @DbReadRetryable
    @GetMapping("/api/v1/users/ai-server")
    @Operation(summary = "AI 서버용 사용자·계좌 정보 조회", description = "AI 서버 전용 API입니다. 일반 사용자 토큰으로 호출할 수 없습니다.")
    public AiUserSnapshotResponse snapshot() {
        return new AiUserSnapshotResponse(service.snapshot());
    }
}
