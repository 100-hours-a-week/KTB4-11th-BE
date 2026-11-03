package com.stock_spoon.river_be.competition.controller;

import com.stock_spoon.river_be.competition.dto.CompetitionNameAvailabilityResponse;
import com.stock_spoon.river_be.competition.service.CompetitionService;
import com.stock_spoon.river_be.config.DbReadRetryable;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController // 반환 객체를 JSON 응답으로 변환한다.
@RequiredArgsConstructor // final 필드인 서비스를 생성자로 주입받는다.
public class CompetitionNameAvailabilityController {
    private final CompetitionService competitionService;

    @DbReadRetryable
    @GetMapping("/api/v3/users/me/competitions/name-availability")
    public CompetitionNameAvailabilityResponse checkNameAvailability(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("competition_name") String competitionName) {
        // 인증된 사용자 ID와 쿼리 파라미터를 서비스에 전달한다.
        return competitionService.checkNameAvailability(Long.parseLong(jwt.getSubject()), competitionName);
    }
}
