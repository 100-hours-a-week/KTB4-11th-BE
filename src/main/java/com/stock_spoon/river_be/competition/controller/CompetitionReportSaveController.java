package com.stock_spoon.river_be.competition.controller;

import com.stock_spoon.river_be.competition.dto.CompetitionReportSaveRequest;
import com.stock_spoon.river_be.competition.dto.CompetitionReportSaveResponse;
import com.stock_spoon.river_be.competition.service.CompetitionReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController // 반환한 응답 객체를 JSON으로 변환한다.
@RequestMapping("/api/v1/competitions")
@RequiredArgsConstructor // 리포트 서비스를 생성자로 주입받는다.
public class CompetitionReportSaveController {
    private final CompetitionReportService reports;

    @PostMapping("/{competitionId}/report")
    public CompetitionReportSaveResponse save(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long competitionId, @Valid @RequestBody CompetitionReportSaveRequest request) {
        // JWT의 사용자 ID와 URL의 대결 ID, 검증된 요청을 저장 서비스에 전달한다.
        reports.save(Long.parseLong(jwt.getSubject()), competitionId, request);
        return new CompetitionReportSaveResponse(competitionId, "success");
    }
}
