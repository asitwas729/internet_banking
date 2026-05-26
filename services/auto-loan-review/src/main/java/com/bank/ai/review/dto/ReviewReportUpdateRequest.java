package com.bank.ai.review.dto;

import com.bank.ai.llm.report.ReviewReport;

/**
 * 리포트 업데이트 요청 DTO.
 */
public record ReviewReportUpdateRequest(
        String status,
        ReviewReport report
) {
}
