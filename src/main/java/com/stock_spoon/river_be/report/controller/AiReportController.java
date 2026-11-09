package com.stock_spoon.river_be.report.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.stock_spoon.river_be.config.DbReadRetryable;
import com.stock_spoon.river_be.report.dto.AiReportResponse;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.report.service.AiReportService;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name = "AI 리포트")
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/orders/{orderId}/ai-report")
public class AiReportController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiReportController.class);
    private final AiReportService reports;

    public AiReportController(AiReportService reports) { this.reports = reports; }

    @DbReadRetryable
    @GetMapping
    @Operation(summary = "주문 AI 판단 근거 조회")
    public AiReportResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long accountId,
            @PathVariable long orderId) {
        long userId;
        try { userId = Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException error) {
            log.warn("event=ai_report_rejected stage=identity_resolution accountId={} orderId={} code=INVALID_TOKEN", accountId, orderId);
            throw new OrderException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "인증 정보를 확인하세요.");
        }
        return reports.get(userId, accountId, orderId);
    }
}
