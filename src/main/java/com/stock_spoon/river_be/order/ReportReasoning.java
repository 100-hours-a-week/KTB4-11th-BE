package com.stock_spoon.river_be.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Embeddable
public record ReportReasoning(
        @Column(nullable = false, length = 255) @NotBlank @Size(max = 255) String label,
        @Column(nullable = false, columnDefinition = "TEXT") @NotBlank @Size(max = 16000) String body) {
    public ReportReasoning {
        if (label == null || label.isBlank() || label.length() > 255
                || body == null || body.isBlank() || body.length() > 16000) {
            throw new IllegalArgumentException("판단 항목의 제목과 내용을 확인하세요.");
        }
    }
}
