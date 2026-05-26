package com.bank.ai.llm.report;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.agent.FallbackReason;
import com.bank.ai.agent.RiskLevel;
import com.bank.ai.agent.SimulationResult;
import com.bank.ai.llm.policy.PolicyIndex;
import com.bank.ai.rule.domain.Track;
import com.bank.ai.rule.domain.TrackDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingValidatorTest {

    private static final PolicyIndex POLICY = new PolicyIndex(Map.of(
            "A_V1", new PolicyIndex.PolicyEntry("정책 A", "src"),
            "B_V1", new PolicyIndex.PolicyEntry("정책 B", "src"),
            "C_V1", new PolicyIndex.PolicyEntry("정책 C", "src")
    ));
    private final GroundingValidator validator = new GroundingValidator(POLICY);

    private ReviewReport withCitations(Track track, List<String> citationIds) {
        var cs = citationIds.stream()
                .map(id -> new ReviewReport.Citation(id, "src", "text"))
                .toList();
        return new ReviewReport(track, "본문", List.of(), List.of(), "권고", cs, null);
    }

    @Test
    void Track1은_citation_1개_이상이면_통과() {
        var r = withCitations(Track.TRACK_1, List.of("A_V1"));
        assertThat(validator.validate(r).passed()).isTrue();
    }

    @Test
    void Track2는_citation_2개_미만이면_실패() {
        var r = withCitations(Track.TRACK_2, List.of("A_V1"));

        var result = validator.validate(r);

        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anyMatch(s -> s.contains("Track 2 인용 부족"));
    }

    @Test
    void Track2는_citation_2개이상_모두_존재시_통과() {
        var r = withCitations(Track.TRACK_2, List.of("A_V1", "B_V1"));

        assertThat(validator.validate(r).passed()).isTrue();
    }

    @Test
    void 존재하지_않는_citation_id는_실패() {
        var r = withCitations(Track.TRACK_1, List.of("A_V1", "GHOST"));

        var result = validator.validate(r);
        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anyMatch(s -> s.contains("GHOST"));
    }

    @Test
    void riskFactor_citationId_미존재시_실패() {
        var risk = new ReviewReport.RiskFactor("X", "위험", 0.5, "GHOST");
        var r = new ReviewReport(Track.TRACK_3, "본문",
                List.of(risk), List.of(),
                "권고",
                List.of(new ReviewReport.Citation("A_V1", "src", "text")),
                null);

        assertThat(validator.validate(r).passed()).isFalse();
    }

    @Test
    void riskFactor_citationId_null은_허용() {
        var risk = new ReviewReport.RiskFactor("X", "위험", 0.5, null);
        var r = new ReviewReport(Track.TRACK_3, "본문",
                List.of(risk), List.of(),
                "권고",
                List.of(new ReviewReport.Citation("A_V1", "src", "text")),
                null);

        assertThat(validator.validate(r).passed()).isTrue();
    }

    @Test
    void strength_citationId_미존재시_실패() {
        var strength = new ReviewReport.Strength("S", "강점", "GHOST");
        var r = new ReviewReport(Track.TRACK_1, "본문",
                List.of(),
                List.of(strength),
                "권고",
                List.of(new ReviewReport.Citation("A_V1", "src", "text")),
                null);

        assertThat(validator.validate(r).passed()).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────
    // validateNumericClaims (A5)
    // ─────────────────────────────────────────────────────────────────────

    private static final TrackDecision TRACK3_DECISION =
            new TrackDecision(Track.TRACK_3, List.of(), 0.35, 0.65, 0.347, 0.104, "회색지대");

    private static AgentOpinion validOpinion() {
        return AgentOpinion.of(0.65, 0.35, RiskLevel.MEDIUM, List.of(), "요약",
                List.of(new SimulationResult("loan_amount_reduction_20pct",
                        8000L, 60, 0.75, 0.25, "risk_reduced", "제안", false)),
                false);
    }

    @Test
    void 정상_의견은_수치검증_통과() {
        assertThat(validator.validateNumericClaims(validOpinion(), TRACK3_DECISION).passed()).isTrue();
    }

    @Test
    void fallback_의견은_수치검증_건너뜀() {
        var fallback = AgentOpinion.fallback(FallbackReason.AGENT_DISABLED);
        assertThat(validator.validateNumericClaims(fallback, TRACK3_DECISION).passed()).isTrue();
    }

    @Test
    void decisionScore_범위초과시_실패() {
        var opinion = AgentOpinion.of(1.5, 0.35, RiskLevel.MEDIUM, List.of(), "요약",
                List.of(), false);
        var result = validator.validateNumericClaims(opinion, TRACK3_DECISION);
        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anyMatch(s -> s.contains("decisionScore 범위 초과"));
    }

    @Test
    void pdScore_범위초과시_실패() {
        var opinion = AgentOpinion.of(0.65, -0.1, RiskLevel.MEDIUM, List.of(), "요약",
                List.of(), false);
        var result = validator.validateNumericClaims(opinion, TRACK3_DECISION);
        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anyMatch(s -> s.contains("pdScore 범위 초과"));
    }

    @Test
    void decisionScore_드리프트_허용오차초과시_실패() {
        // decision에서 0.65이지만 opinion에 0.99 입력
        var opinion = AgentOpinion.of(0.99, 0.35, RiskLevel.MEDIUM, List.of(), "요약",
                List.of(), false);
        var result = validator.validateNumericClaims(opinion, TRACK3_DECISION);
        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anyMatch(s -> s.contains("decisionScore 드리프트"));
    }

    @Test
    void pdScore_드리프트_허용오차초과시_실패() {
        // decision.pd=0.35이지만 opinion에 0.80 입력
        var opinion = AgentOpinion.of(0.65, 0.80, RiskLevel.MEDIUM, List.of(), "요약",
                List.of(), false);
        var result = validator.validateNumericClaims(opinion, TRACK3_DECISION);
        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anyMatch(s -> s.contains("pdScore 드리프트"));
    }

    @Test
    void 시뮬레이션_점수_범위초과시_실패() {
        var opinion = AgentOpinion.of(0.65, 0.35, RiskLevel.MEDIUM, List.of(), "요약",
                List.of(new SimulationResult("loan_amount_reduction_20pct",
                        8000L, 60, 1.5, 0.25, "risk_reduced", "제안", false)),
                false);
        var result = validator.validateNumericClaims(opinion, TRACK3_DECISION);
        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anyMatch(s -> s.contains("simulation") && s.contains("decisionScore 범위 초과"));
    }
}
