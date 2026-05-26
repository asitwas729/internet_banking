package com.bank.ai.review.listener;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.agent.FallbackReason;
import com.bank.ai.agent.PreReviewAgentService;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 비동기 LLM 파이프라인 리스너 — plan/llm-pipeline.md §7, pre-review-agent-plan.md §아키텍처.
 *
 * <p>흐름:
 * <ol>
 *   <li>PurposeAnalysis — 신청 사유 분석</li>
 *   <li>ReviewReport — 트랙별 심사 리포트 생성</li>
 *   <li>PreReviewAgentService — 에이전트 의견 생성 (30s 타임아웃, A6 신규)</li>
 *   <li>loan-service PATCH — 결과 업데이트 (agent_opinion_json 포함)</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoReviewEventListener {

    static final int AGENT_TIMEOUT_SECONDS = 30;

    private final PurposeAnalysisService purposeAnalysisService;
    private final ReviewReportService reviewReportService;
    private final PreReviewAgentService preReviewAgentService;
    private final LoanServiceClient loanServiceClient;
    private final ObjectMapper objectMapper;

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

            // Step 3: PreReviewAgentService (30s 타임아웃)
            AgentOpinion opinion = runAgentWithTimeout(event);
            log.info("Agent opinion: riskLevel={} fallback={}", opinion.riskLevel(), opinion.fallbackReason());

            String agentOpinionJson = serializeOpinion(opinion, event.revId());

            // Step 4: loan-service 에 결과 전송
            loanServiceClient.updateReport(event.revId(),
                    new ReviewReportUpdateRequest("DONE", report, agentOpinionJson));
            log.info("Async LLM pipeline completed for revId: {}", event.revId());

        } catch (Exception e) {
            log.error("Async LLM pipeline failed for revId: {}", event.revId(), e);
            try {
                loanServiceClient.updateReport(event.revId(),
                        new ReviewReportUpdateRequest("FAILED", null, null));
            } catch (Exception callbackEx) {
                log.error("Failed to send FAILED status callback for revId: {}", event.revId(), callbackEx);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────

    private AgentOpinion runAgentWithTimeout(AutoReviewEvaluatedEvent event) {
        var future = CompletableFuture.supplyAsync(
                () -> preReviewAgentService.run(event.revId(), event.request(), event.decision()))
                .orTimeout(AGENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                log.warn("PreReviewAgentService timeout {}s revId={}", AGENT_TIMEOUT_SECONDS, event.revId());
                return AgentOpinion.fallback(FallbackReason.AGENT_TIMEOUT);
            }
            log.error("PreReviewAgentService error revId={}", event.revId(), e.getCause());
            return AgentOpinion.fallback(FallbackReason.TOOL_ERROR);
        }
    }

    private String serializeOpinion(AgentOpinion opinion, Long revId) {
        try {
            return objectMapper.writeValueAsString(opinion);
        } catch (JsonProcessingException e) {
            log.error("AgentOpinion 직렬화 실패 revId={}", revId, e);
            return null;
        }
    }

    private PurposeAnalysis analyzePurpose(AutoReviewRequest req) {
        var input = new PurposeAnalysisInput(
                "persona_summary_stub",
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
                "persona_summary_stub",
                event.request().productCode(),
                purpose
        );
        return reviewReportService.generate(input);
    }
}
