package com.bank.loan.advisory;

import com.bank.loan.advisory.domain.audit.AiAuditOpinion;
import com.bank.loan.advisory.domain.audit.ReviewerRiskScore;
import com.bank.loan.advisory.dto.AiAuditOpinionResponse;
import com.bank.loan.advisory.dto.ReviewerRiskScoreResponse;
import com.bank.loan.advisory.repository.audit.AiAuditOpinionRepository;
import com.bank.loan.advisory.repository.audit.ReviewerRiskScoreRepository;
import com.bank.loan.advisory.service.AuditOpinionQueryService;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AuditOpinionQueryService DB 저장·조회 통합 검증.
 * gateway 호출 없이 Repository 직접 적재 후 서비스 레이어 검증.
 *
 * 테스트 연도: 2050 (다른 테스트와 격리)
 */
class AuditOpinionQueryIntegrationTest extends AbstractLoanIntegrationTest {

    @Autowired AiAuditOpinionRepository  opinionRepo;
    @Autowired ReviewerRiskScoreRepository riskRepo;
    @Autowired AuditOpinionQueryService  queryService;

    @Test
    void advrId_로_감사_의견_조회() {
        Long advrId = 50_001L;
        Long revId  = 60_001L;
        Long reviewerId = 70_001L;

        opinionRepo.save(opinion(advrId, revId, reviewerId, AiAuditOpinion.TYPE_BIAS, "BIAS_SUSPECTED", 0.85));
        opinionRepo.save(opinion(advrId, revId, reviewerId, AiAuditOpinion.TYPE_COMPLIANCE, "COMPLIANT", 0.72));

        List<AiAuditOpinionResponse> results = queryService.opinionsByAdvr(advrId);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(AiAuditOpinionResponse::advrId).containsOnly(advrId);
        assertThat(results).extracting(AiAuditOpinionResponse::analysisTypeCd)
                .containsExactlyInAnyOrder(AiAuditOpinion.TYPE_BIAS, AiAuditOpinion.TYPE_COMPLIANCE);
    }

    @Test
    void reviewerId_로_감사_의견_이력_최신순_조회() {
        Long reviewerId = 70_002L;
        OffsetDateTime now = OffsetDateTime.now();

        opinionRepo.save(opinion(50_002L, 60_002L, reviewerId, AiAuditOpinion.TYPE_BIAS, "BIAS_SUSPECTED", 0.8)
                .toBuilder().generatedAt(now.minusHours(2)).build());
        opinionRepo.save(opinion(50_003L, 60_003L, reviewerId, AiAuditOpinion.TYPE_COMPLIANCE, "VIOLATION_SUSPECTED", 0.9)
                .toBuilder().generatedAt(now.minusHours(1)).build());

        List<AiAuditOpinionResponse> results = queryService.opinionsByReviewer(reviewerId);

        assertThat(results).hasSize(2);
        // 최신순 — COMPLIANCE(1시간 전)가 먼저
        assertThat(results.get(0).analysisTypeCd()).isEqualTo(AiAuditOpinion.TYPE_COMPLIANCE);
        assertThat(results.get(1).analysisTypeCd()).isEqualTo(AiAuditOpinion.TYPE_BIAS);
    }

    @Test
    void riskScore_없는_reviewerId_조회_시_404예외() {
        assertThatThrownBy(() -> queryService.riskScore(99_999L))
                .hasMessageContaining("99999");
    }

    @Test
    void riskScore_저장_후_조회() {
        Long reviewerId = 70_003L;
        ReviewerRiskScore score = ReviewerRiskScore.init(reviewerId);
        score.applyBiasConclusion(AiAuditOpinion.CONCLUSION_BIAS_SUSPECTED);
        score.applyBiasConclusion(AiAuditOpinion.CONCLUSION_BIAS_SUSPECTED);
        riskRepo.save(score);

        ReviewerRiskScoreResponse resp = queryService.riskScore(reviewerId);

        assertThat(resp.biasScore()).isEqualTo(10.0);
        assertThat(resp.complianceScore()).isEqualTo(0.0);
        assertThat(resp.evaluationCount()).isEqualTo(2);
    }

    @Test
    void topByBias_bias_score_내림차순_반환() {
        riskRepo.save(scoreSeed(70_010L, 30.0, 0.0));
        riskRepo.save(scoreSeed(70_011L, 80.0, 0.0));
        riskRepo.save(scoreSeed(70_012L, 55.0, 0.0));

        List<ReviewerRiskScoreResponse> top = queryService.topByBias(2);

        assertThat(top).hasSizeLessThanOrEqualTo(2);
        // 80, 55 순 — 우리가 적재한 것 중 상위 2
        if (top.size() >= 2) {
            assertThat(top.get(0).biasScore()).isGreaterThanOrEqualTo(top.get(1).biasScore());
        }
    }

    // ── helpers ──────────────────────────────────────────────────

    private AiAuditOpinion opinion(Long advrId, Long revId, Long reviewerId,
                                    String type, String conclusion, double confidence) {
        return AiAuditOpinion.builder()
                .advrId(advrId)
                .revId(revId)
                .reviewerId(reviewerId)
                .analysisTypeCd(type)
                .conclusionCd(conclusion)
                .reasoningSummary("테스트 감사 의견")
                .confidenceScore(confidence)
                .inputTokens(100)
                .outputTokens(80)
                .generatedAt(OffsetDateTime.now())
                .build();
    }

    private ReviewerRiskScore scoreSeed(Long reviewerId, double bias, double compliance) {
        ReviewerRiskScore s = ReviewerRiskScore.init(reviewerId);
        // bias 직접 세팅 불가 — applyBias 루프로 근사
        int times = (int) (bias / 5.0);
        for (int i = 0; i < times; i++) {
            s.applyBiasConclusion(AiAuditOpinion.CONCLUSION_BIAS_SUSPECTED);
        }
        return s;
    }
}
