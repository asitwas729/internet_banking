package com.bank.ai.review.listener;

import com.bank.ai.llm.purpose.PurposeAnalysis;
import com.bank.ai.llm.purpose.PurposeAnalysisInput;
import com.bank.ai.llm.purpose.PurposeAnalysisService;
import com.bank.ai.llm.report.ReviewReport;
import com.bank.ai.llm.report.ReviewReportInput;
import com.bank.ai.llm.report.ReviewReportService;
import com.bank.ai.review.client.LoanServiceClient;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.ReviewReportUpdateRequest;
import com.bank.ai.review.event.AutoReviewEvaluatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 비동기 LLM 파이프라인 리스너 — plan/llm-pipeline.md §7.
 *
 * <p>흐름:
 * 1. PurposeAnalysis (신청 사유 분석)
 * 2. ReviewReport (트랙별 리포트 생성)
 * 3. TODO: loan-service PATCH (결과 업데이트)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoReviewEventListener {

    private final PurposeAnalysisService purposeAnalysisService;
    private final ReviewReportService reviewReportService;
    private final LoanServiceClient loanServiceClient;

    @Async("llmExecutor")
    @EventListener
    public void handleAutoReviewEvaluated(AutoReviewEvaluatedEvent event) {
        if (event.revId() == null) {
            log.warn("Async LLM pipeline skipped: revId is null (likely standalone /evaluate call)");
            return;
        }

        log.info("Starting async LLM pipeline for revId: {}", event.revId());

        try {
            // Step 1: Purpose Analysis
            PurposeAnalysis purpose = analyzePurpose(event.request());
            log.info("Purpose analysis done: {}", purpose);

            // Step 2: Review Report
            ReviewReport report = generateReport(event, purpose);
            log.info("Review report generated: track={}", report.track());

            // Step 3: loan-service 에 결과 전송 (PATCH /api/loan-reviews/{revId}/report)
            loanServiceClient.updateReport(event.revId(), new ReviewReportUpdateRequest("DONE", report));
            log.info("Async LLM pipeline completed and callback sent for revId: {}", event.revId());

        } catch (Exception e) {
            log.error("Async LLM pipeline failed for revId: {}", event.revId(), e);
            // 실패 시 FAILED 상태 전송
            try {
                loanServiceClient.updateReport(event.revId(), new ReviewReportUpdateRequest("FAILED", null));
            } catch (Exception callbackEx) {
                log.error("Failed to send FAILED status callback for revId: {}", event.revId(), callbackEx);
            }
        }
    }

    private PurposeAnalysis analyzePurpose(AutoReviewRequest req) {
        var input = new PurposeAnalysisInput(
                "persona_summary_stub", // TODO: 실제 요약 로직
                req.purposeCd(),
                req.productCode(),
                req.requestedAmountKw() != null ? req.requestedAmountKw() / 10000 : 0,
                req.requestedPeriodMo()
        );
        return purposeAnalysisService.analyze(input);
    }

    private ReviewReport generateReport(AutoReviewEvaluatedEvent event, PurposeAnalysis purpose) {
        var input = new ReviewReportInput(
                event.decision().track(),
                event.inference().pdScore() != null ? event.inference().pdScore() : 0.0,
                event.inference().decisionScore(),
                event.decision().pdThreshold(),
                event.decision().safetyMarginThreshold(),
                event.decision().hardFails(),
                "persona_summary_stub", // TODO: 실제 요약 로직
                event.request().productCode(),
                purpose
        );
        return reviewReportService.generate(input);
    }
}
