package com.stock_spoon.river_be.competition.controller;

import com.stock_spoon.river_be.config.DbReadRetryable;
import com.stock_spoon.river_be.competition.dto.CompetitionCreateRequest;
import com.stock_spoon.river_be.competition.dto.CompetitionCreateResponse;
import com.stock_spoon.river_be.competition.dto.CompetitionListResponse;
import com.stock_spoon.river_be.competition.dto.CompetitionDetailResponse;
import com.stock_spoon.river_be.competition.dto.CompetitionResultResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.stock_spoon.river_be.competition.service.CompetitionService;
import com.stock_spoon.river_be.competition.logging.CompetitionCreationLog;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import java.util.Map;
import java.util.stream.Collectors;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController // 요청을 처리하는 컨트롤러로 등록하고 반환 객체를 JSON 응답으로 변환한다.
@RequestMapping("/api/v2/users/me/competitions") // 이 컨트롤러의 공통 요청 경로를 지정한다.
@RequiredArgsConstructor
public class CompetitionController {

    private final CompetitionService competitionService;

    @DbReadRetryable
    @GetMapping("/{competitionId}/result") // 완료된 대결의 최종 성적과 양측 비교 그래프를 조회한다.
    public CompetitionResultResponse getCompetitionResult(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long competitionId) {
        return competitionService.getCompetitionResult(Long.parseLong(jwt.getSubject()), competitionId);
    }

    @DbReadRetryable
    @GetMapping("/{competitionId}") // 대결 ID는 URL에서, 조회할 사용자 ID는 인증된 JWT에서 받는다.
    public CompetitionDetailResponse getCompetitionDetail(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long competitionId) {
        return competitionService.getCompetitionDetail(Long.parseLong(jwt.getSubject()), competitionId);
    }

    @DbReadRetryable
    @GetMapping // JWT의 사용자 ID로 본인의 대결만 조회한다.
    public CompetitionListResponse listCompetitions(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String state) {
        // 조회 위치·개수·상태는 쿼리에서 받고, 사용자 ID는 신뢰할 수 있는 JWT에서만 읽는다.
        return competitionService.listCompetitions(Long.parseLong(jwt.getSubject()), cursor, limit, state);
    }

    @PostMapping // 공통 경로로 들어오는 POST 요청을 처리한다.
    @ResponseStatus(HttpStatus.CREATED) // 정상 반환 시 HTTP 상태 코드를 201 Created로 지정한다.
    public CompetitionCreateResponse createCompetition(
            @AuthenticationPrincipal Jwt jwt, // 인증된 사용자의 JWT를 주입한다. subject에서 사용자 ID를 읽을 수 있다.
            @Valid @RequestBody CompetitionCreateRequest request) { // JSON 요청 본문을 DTO로 변환한다.
        try (var creationLog = new CompetitionCreationLog()) {
            try {
                long userId = Long.parseLong(jwt.getSubject());
                CompetitionCreationLog.userId(userId);
                CompetitionCreationLog.stage("transaction_begin");
                // Spring의 트랜잭션 프록시가 커밋한 뒤 반환하므로 커밋 실패도 여기서 기록한다.
                return competitionService.createCompetition(userId, request);
            } catch (RuntimeException error) {
                creationLog.failure(error);
                throw error;
            }
        }
    }

    // @Valid·JSON 변환은 생성 메서드 실행 전에 실패하므로 별도 진입점에서 기록한다.
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> invalidCreateRequest(Exception error) {
        try (var creationLog = new CompetitionCreationLog()) {
            String fields = "none";
            if (error instanceof MethodArgumentNotValidException validation) {
                CompetitionCreationLog.stage("request_validation");
                fields = validation.getBindingResult().getFieldErrors().stream()
                        .map(field -> field.getField() + ":" + field.getCode()).distinct().sorted().collect(Collectors.joining(","));
            } else {
                CompetitionCreationLog.stage("request_deserialization");
            }
            creationLog.invalidRequest(error, fields);
        }
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "요청 형식이 올바르지 않습니다."));
    }

}
