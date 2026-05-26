package com.bank.ai.llm.report;

import com.bank.ai.llm.policy.PolicyIndex;
import com.bank.ai.rule.domain.Track;
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
}
