package com.bank.loan.advisory.agent;

import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.domain.ReviewAdvisorySignal;
import com.bank.loan.advisory.domain.audit.AiAuditOpinion;
import com.bank.loan.advisory.domain.audit.ReviewerRiskScore;
import com.bank.loan.advisory.dto.PolicyCitationResponse;
import com.bank.loan.advisory.engine.rules.BiasApprovalRateDeviationRule;
import com.bank.loan.advisory.engine.rules.BiasRejectRateDeviationRule;
import com.bank.loan.advisory.engine.rules.DsrThresholdOverrideRule;
import com.bank.loan.advisory.engine.rules.LtvThresholdOverrideRule;
import com.bank.loan.advisory.engine.rules.PeerDecisionDivergenceRule;
import com.bank.loan.advisory.gateway.AiGatewayClient;
import com.bank.loan.advisory.gateway.GatewayAnalysisRequest;
import com.bank.loan.advisory.gateway.GatewayAnalysisRequest.GatewayRagChunk;
import com.bank.loan.advisory.gateway.GatewayAnalysisRequest.GatewaySignalSummary;
import com.bank.loan.advisory.gateway.GatewayAnalysisResponse;
import com.bank.loan.advisory.rag.PiiMaskingUtil;
import com.bank.loan.advisory.rag.PolicyCitationRetriever;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.advisory.repository.ReviewAdvisorySignalRepository;
import com.bank.loan.advisory.repository.audit.AiAuditOpinionRepository;
import com.bank.loan.advisory.repository.audit.ReviewerRiskScoreRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 감사/공정성 Agent. 룰 엔진이 WARN 이상 리포트를 발행한 뒤 호출.
 *
 * 처리 순서:
 *   1. 리포트·신호 로드 → BIAS / COMPLIANCE 그룹 분류
 *   2. 심사관 의견서(revRemark) 로드 + PII 마스킹
 *   3. 정책 RAG 청크 조회
 *   4. review-ai-gateway 호출 (best-effort — 실패 시 경고만)
 *   5. AiAuditOpinion 저장 + ReviewerRiskScore UPSERT
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditFairnessAgent {

    private static final Set<String> BIAS_RULES = Set.of(
            BiasApprovalRateDeviationRule.RULE_CD,
            BiasRejectRateDeviationRule.RULE_CD,
            PeerDecisionDivergenceRule.RULE_CD
    );
    private static final Set<String> COMPLIANCE_RULES = Set.of(
            DsrThresholdOverrideRule.RULE_CD,
            LtvThresholdOverrideRule.RULE_CD
    );

    private final ReviewAdvisoryReportRepository  reportRepo;
    private final ReviewAdvisorySignalRepository  signalRepo;
    private final LoanReviewRepository            loanReviewRepo;
    private final AiGatewayClient                 gatewayClient;
    private final PolicyCitationRetriever         citationRetriever;
    private final AiAuditOpinionRepository        opinionRepo;
    private final ReviewerRiskScoreRepository     riskScoreRepo;

    @Transactional
    public void analyzeReports(List<Long> advrIds) {
        if (advrIds == null || advrIds.isEmpty()) return;

        List<ReviewAdvisoryReport> reports = reportRepo.findAllById(advrIds).stream()
                .filter(r -> ReviewAdvisoryReport.SEVERITY_WARN.equals(r.getSeverityCd())
                          || ReviewAdvisoryReport.SEVERITY_CRITICAL.equals(r.getSeverityCd()))
                .toList();

        if (reports.isEmpty()) return;

        // revId + reviewerId 기준으로 그룹화
        Map<Long, List<ReviewAdvisoryReport>> byRevId = reports.stream()
                .collect(Collectors.groupingBy(ReviewAdvisoryReport::getRevId));

        for (Map.Entry<Long, List<ReviewAdvisoryReport>> entry : byRevId.entrySet()) {
            Long revId = entry.getKey();
            List<ReviewAdvisoryReport> revReports = entry.getValue();
            processRevGroup(revId, revReports);
        }
    }

    private void processRevGroup(Long revId, List<ReviewAdvisoryReport> reports) {
        Long reviewerId = reports.stream()
                .map(ReviewAdvisoryReport::getTargetReviewerId)
                .filter(id -> id != null)
                .findFirst().orElse(null);

        String maskedOpinion = loadMaskedOpinion(revId);
        List<GatewaySignalSummary> allSignals = loadSignals(reports);

        List<GatewaySignalSummary> biasSignals = allSignals.stream()
                .filter(s -> BIAS_RULES.contains(s.ruleCd())).toList();
        List<GatewaySignalSummary> complianceSignals = allSignals.stream()
                .filter(s -> COMPLIANCE_RULES.contains(s.ruleCd())).toList();

        Long primaryAdvrId = reports.get(0).getAdvrId();

        if (!biasSignals.isEmpty()) {
            List<GatewayRagChunk> ragChunks = fetchRagChunks(primaryAdvrId, "BIAS", biasSignals);
            callAndSave(revId, reviewerId, primaryAdvrId,
                    AiAuditOpinion.TYPE_BIAS, biasSignals, maskedOpinion, ragChunks);
        }

        if (!complianceSignals.isEmpty()) {
            List<GatewayRagChunk> ragChunks = fetchRagChunks(primaryAdvrId, "COMPLIANCE", complianceSignals);
            callAndSave(revId, reviewerId, primaryAdvrId,
                    AiAuditOpinion.TYPE_COMPLIANCE, complianceSignals, maskedOpinion, ragChunks);
        }
    }

    private void callAndSave(Long revId, Long reviewerId, Long advrId,
                             String analysisType,
                             List<GatewaySignalSummary> signals,
                             String maskedOpinion,
                             List<GatewayRagChunk> ragChunks) {
        try {
            GatewayAnalysisResponse resp = gatewayClient.analyze(new GatewayAnalysisRequest(
                    analysisType, revId, reviewerId, maskedOpinion, signals, ragChunks));

            AiAuditOpinion opinion = AiAuditOpinion.builder()
                    .advrId(advrId)
                    .revId(revId)
                    .reviewerId(reviewerId)
                    .analysisTypeCd(analysisType)
                    .conclusionCd(resp.conclusion())
                    .reasoningSummary(resp.reasoningSummary())
                    .confidenceScore(resp.confidenceScore())
                    .inputTokens(resp.inputTokens())
                    .outputTokens(resp.outputTokens())
                    .generatedAt(OffsetDateTime.now())
                    .build();
            opinionRepo.save(opinion);

            if (reviewerId != null) {
                updateRiskScore(reviewerId, analysisType, resp.conclusion());
            }

            log.info("감사 의견 저장 — revId={} type={} conclusion={} confidence={}",
                    revId, analysisType, resp.conclusion(), resp.confidenceScore());
        } catch (Exception e) {
            log.warn("AuditFairnessAgent 분석 실패 (무시) — revId={} type={}: {}",
                    revId, analysisType, e.getMessage());
        }
    }

    private void updateRiskScore(Long reviewerId, String analysisType, String conclusionCd) {
        ReviewerRiskScore score = riskScoreRepo.findByReviewerId(reviewerId)
                .orElseGet(() -> ReviewerRiskScore.init(reviewerId));

        if (AiAuditOpinion.TYPE_BIAS.equals(analysisType)) {
            score.applyBiasConclusion(conclusionCd);
        } else {
            score.applyComplianceConclusion(conclusionCd);
        }
        riskScoreRepo.save(score);
    }

    private String loadMaskedOpinion(Long revId) {
        return loanReviewRepo.findById(revId)
                .map(LoanReview::getRevRemark)
                .map(PiiMaskingUtil::mask)
                .orElse(null);
    }

    private List<GatewaySignalSummary> loadSignals(List<ReviewAdvisoryReport> reports) {
        List<GatewaySignalSummary> result = new ArrayList<>();
        for (ReviewAdvisoryReport report : reports) {
            List<ReviewAdvisorySignal> signals = signalRepo.findByAdvrIdOrderByObservedAtAsc(report.getAdvrId());
            for (ReviewAdvisorySignal s : signals) {
                result.add(new GatewaySignalSummary(
                        report.getAdvisoryTypeCd(),
                        report.getSeverityCd(),
                        s.getSignalMetric(),
                        s.getObservedValue() != null ? s.getObservedValue().doubleValue() : 0.0,
                        s.getThresholdValue() != null ? s.getThresholdValue().doubleValue() : 0.0
                ));
            }
        }
        return result;
    }

    private List<GatewayRagChunk> fetchRagChunks(Long advrId, String type,
                                                   List<GatewaySignalSummary> signals) {
        try {
            String query = signals.stream()
                    .map(GatewaySignalSummary::ruleCd)
                    .collect(Collectors.joining(" "));
            PolicyCitationResponse citation = citationRetriever.retrieve(advrId, type, query, null);
            return citation.citations().stream()
                    .map(c -> new GatewayRagChunk(c.docTitle() + " > " + c.sectionPath(), c.chunkText()))
                    .toList();
        } catch (Exception e) {
            log.debug("RAG 청크 조회 실패 — advrId={}: {}", advrId, e.getMessage());
            return List.of();
        }
    }
}
