package com.stock_spoon.river_be.report.controller;

import com.stock_spoon.river_be.report.dto.AiReportResponse;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.report.service.AiReportService;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/orders/{orderId}/ai-report")
public class AiReportController {
    private final AiReportService reports;

    public AiReportController(AiReportService reports) { this.reports = reports; }

    @GetMapping
    public AiReportResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long accountId,
            @PathVariable long orderId) {
        long userId;
        try { userId = Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException error) {
            throw new OrderException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "인증 정보를 확인하세요.");
        }
        return reports.get(userId, accountId, orderId);
    }
}
