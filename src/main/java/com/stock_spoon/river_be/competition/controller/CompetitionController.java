package com.stock_spoon.river_be.competition.controller;

import com.stock_spoon.river_be.competition.dto.CompetitionCreateRequest;
import com.stock_spoon.river_be.competition.dto.CompetitionCreateResponse;
import com.stock_spoon.river_be.competition.dto.CompetitionListResponse;
import com.stock_spoon.river_be.competition.dto.CompetitionDetailResponse;
import com.stock_spoon.river_be.competition.dto.CompetitionResultResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import com.stock_spoon.river_be.competition.service.CompetitionService;
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

    @GetMapping("/{competitionId}/result") // 완료된 대결의 최종 성적과 양측 비교 그래프를 조회한다.
    public CompetitionResultResponse getCompetitionResult(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long competitionId) {
        return competitionService.getCompetitionResult(Long.parseLong(jwt.getSubject()), competitionId);
    }

    @GetMapping("/{competitionId}") // 대결 ID는 URL에서, 조회할 사용자 ID는 인증된 JWT에서 받는다.
    public CompetitionDetailResponse getCompetitionDetail(@AuthenticationPrincipal Jwt jwt,
            @PathVariable Long competitionId) {
        return competitionService.getCompetitionDetail(Long.parseLong(jwt.getSubject()), competitionId);
    }

    @GetMapping // JWT의 사용자 ID로 본인의 대결만 조회한다.
    public CompetitionListResponse listCompetitions(@AuthenticationPrincipal Jwt jwt) {
        return competitionService.listCompetitions(Long.parseLong(jwt.getSubject()));
    }

    @PostMapping // 공통 경로로 들어오는 POST 요청을 처리한다.
    @ResponseStatus(HttpStatus.CREATED) // 정상 반환 시 HTTP 상태 코드를 201 Created로 지정한다.
    public CompetitionCreateResponse createCompetition(
            @AuthenticationPrincipal Jwt jwt, // 인증된 사용자의 JWT를 주입한다. subject에서 사용자 ID를 읽을 수 있다.
            @Valid @RequestBody CompetitionCreateRequest request) { // JSON 요청 본문을 DTO로 변환한다.
        return competitionService.createCompetition(Long.parseLong(jwt.getSubject()), request);
    }


}
