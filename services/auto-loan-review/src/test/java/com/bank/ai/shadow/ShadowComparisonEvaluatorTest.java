package com.bank.ai.shadow;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.agent.FallbackReason;
import com.bank.ai.agent.RiskLevel;
import com.bank.ai.rule.domain.Track;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShadowComparisonEvaluator 단위 테스트 — phase-b-operational.md §B3.
 *
 * <p>스프링 컨텍스트 없이 순수 단위 테스트.
 */
class ShadowComparisonEvaluatorTest {

    private ShadowComparisonEvaluator evaluator;

    private static final ShadowRunProperties PROPS =
            new ShadowRunProperties(true, "stub-v1", "v1", 0.10, 1.0, 45);

    @BeforeEach
    void setUp() {
        evaluator = new ShadowComparisonEvaluator(PROPS);
    }

    // ── TC 1: 동일 의견 → diverged=false ───────────────────────────────

    @Test
    void sameOpinion_notDiverged() {
        AgentOpinion prod   = opinion(0.80, RiskLevel.LOW,    false);
        AgentOpinion shadow = opinion(0.80, RiskLevel.LOW,    false);

        ShadowComparisonResult result = evaluator.evaluate(
                1L, prod, shadow, Track.TRACK_1, "stub-v1", "v1");

        assertThat(result.diverged()).isFalse();
        assertThat(result.divergeReasons()).isEmpty();
    }

    // ── TC 2: riskLevel 불일치 → RISK_LEVEL_MISMATCH ───────────────────

    @Test
    void riskLevelMismatch_diverged() {
        AgentOpinion prod   = opinion(0.70, RiskLevel.LOW,    false);
        AgentOpinion shadow = opinion(0.70, RiskLevel.MEDIUM, false);

        ShadowComparisonResult result = evaluator.evaluate(
                2L, prod, shadow, Track.TRACK_3, "stub-v1", "v1");

        assertThat(result.diverged()).isTrue();
        assertThat(result.divergeReasons()).contains("RISK_LEVEL_MISMATCH");
    }

    // ── TC 3: decisionScore 차 > threshold → DECISION_SCORE_GAP ────────

    @Test
    void decisionScoreGap_aboveThreshold_diverged() {
        AgentOpinion prod   = opinion(0.90, RiskLevel.LOW,  false);
        AgentOpinion shadow = opinion(0.70, RiskLevel.LOW,  false);  // gap=0.20 > 0.10

        ShadowComparisonResult result = evaluator.evaluate(
                3L, prod, shadow, Track.TRACK_3, "stub-v1", "v1");

        assertThat(result.diverged()).isTrue();
        assertThat(result.divergeReasons()).contains("DECISION_SCORE_GAP");
    }

    @Test
    void decisionScoreGap_belowThreshold_notDiverged() {
        AgentOpinion prod   = opinion(0.80, RiskLevel.LOW, false);
        AgentOpinion shadow = opinion(0.75, RiskLevel.LOW, false);  // gap=0.05 ≤ 0.10

        ShadowComparisonResult result = evaluator.evaluate(
                4L, prod, shadow, Track.TRACK_3, "stub-v1", "v1");

        assertThat(result.diverged()).isFalse();
    }

    // ── TC 4: disagreement 불일치 → DISAGREEMENT_MISMATCH ───────────────

    @Test
    void disagreementMismatch_diverged() {
        AgentOpinion prod   = opinion(0.75, RiskLevel.MEDIUM, true);
        AgentOpinion shadow = opinion(0.75, RiskLevel.MEDIUM, false);

        ShadowComparisonResult result = evaluator.evaluate(
                5L, prod, shadow, Track.TRACK_3, "stub-v1", "v1");

        assertThat(result.diverged()).isTrue();
        assertThat(result.divergeReasons()).contains("DISAGREEMENT_MISMATCH");
    }

    // ── TC 5: fallback 의견 → 비교 생략, diverged=false ─────────────────

    @Test
    void fallbackOpinion_skipsComparison() {
        AgentOpinion prod   = AgentOpinion.fallback(FallbackReason.TOOL_ERROR);
        AgentOpinion shadow = opinion(0.80, RiskLevel.LOW, false);

        ShadowComparisonResult result = evaluator.evaluate(
                6L, prod, shadow, Track.TRACK_3, "stub-v1", "v1");

        assertThat(result.diverged()).isFalse();
        assertThat(result.divergeReasons()).isEmpty();
    }

    // ── TC 6: 복수 사유 동시 발생 ────────────────────────────────────────

    @Test
    void multipleReasons_allRecorded() {
        AgentOpinion prod   = opinion(0.90, RiskLevel.LOW,  true);
        AgentOpinion shadow = opinion(0.70, RiskLevel.HIGH, false);  // score gap + risk + disagreement

        ShadowComparisonResult result = evaluator.evaluate(
                7L, prod, shadow, Track.TRACK_3, "stub-v1", "v1");

        assertThat(result.diverged()).isTrue();
        assertThat(result.divergeReasons())
                .contains("RISK_LEVEL_MISMATCH", "DECISION_SCORE_GAP", "DISAGREEMENT_MISMATCH");
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private static AgentOpinion opinion(double decisionScore, RiskLevel riskLevel, boolean disagreement) {
        return AgentOpinion.of(decisionScore, 0.1, riskLevel, List.of(), "테스트 요약", List.of(), disagreement);
    }
}
