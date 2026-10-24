package com.stock_spoon.river_be.competition.controller;

import com.stock_spoon.river_be.config.DbReadRetryable;
import com.stock_spoon.river_be.competition.dto.CompetitionReportResponse;
import com.stock_spoon.river_be.competition.service.CompetitionReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/users/me/competitions/{competitionId}/report")
public class CompetitionReportController {
    private final CompetitionReportService reports;

    @DbReadRetryable
    @GetMapping
    public CompetitionReportResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long competitionId) {
        return reports.get(Long.parseLong(jwt.getSubject()), competitionId);
    }
}
