package com.stock_spoon.river_be.competition.controller;

import com.stock_spoon.river_be.competition.dto.CompetitionAiSetupCompleteRequest;
import com.stock_spoon.river_be.competition.service.CompetitionAiSetupCompletionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name = "AI 서버 전용")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/competitions")
public class CompetitionAiSetupCallbackController {
    private final CompetitionAiSetupCompletionService completion;

    @PostMapping("/{competitionId}/ai-setup/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "AI 간 대결 설정 완료", description = "모든 참가자의 설정을 저장한 AI 서버가 원본 event_id로 통지합니다. 실제 시작은 장중 스케줄러가 처리합니다.")
    public void complete(@AuthenticationPrincipal Jwt jwt, @PathVariable long competitionId,
                         @Valid @RequestBody CompetitionAiSetupCompleteRequest request) {
        completion.complete(Long.parseLong(jwt.getSubject()), competitionId, request);
    }
}
