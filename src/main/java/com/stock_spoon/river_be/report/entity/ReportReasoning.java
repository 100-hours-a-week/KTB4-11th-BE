package com.stock_spoon.river_be.report.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Embeddable
public record ReportReasoning(
        @Column(nullable = false, length = 255) @NotBlank @Size(max = 255) String label,
        @Column(nullable = false, columnDefinition = "TEXT") @NotBlank @Size(max = 16000) String body) {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReportReasoning.class);
    public ReportReasoning {
        if (label == null || label.isBlank() || label.length() > 255
                || body == null || body.isBlank() || body.length() > 16000) {
            log.warn("event=ai_report_input_rejected stage=reasoning_validation code=INVALID_REASONING_LABEL_OR_BODY");
            throw new IllegalArgumentException("판단 항목의 제목과 내용을 확인하세요.");
        }
    }
}
