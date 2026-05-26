package com.bank.ai.llm.report;

import com.bank.ai.llm.policy.PolicyIndex;
import com.bank.ai.rule.domain.Track;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ReviewReport grounding 검증 — plan/llm-pipeline.md §5.4.
 *
 * <p>LLM 이 산출한 리포트의 citation·riskFactor·strength 가 모두 실제 정책 id 를 참조하는지 검사.
 * Track 2 는 법령·정책 인용 ≥ 2 강제 (감독·소송 대비).
 *
 * <p>실패 시 호출 측이 {@code TemplateFallback} 로 우회 — LLM 환각 인용 차단.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroundingValidator {

    /** Track 2 (자동 반려) 가 가져야 할 최소 인용 수. */
    public static final int MIN_CITATIONS_TRACK_2 = 2;

    private final PolicyIndex policyIndex;

    /**
     * @return 첫 위반 사유 (없으면 빈 Optional). 다중 위반도 첫 1건만 — fallback 단계 reasoning 용.
     */
    public ValidationResult validate(ReviewReport report) {
        List<String> issues = new ArrayList<>();

        if (report.track() == Track.TRACK_2
                && report.citations().size() < MIN_CITATIONS_TRACK_2) {
            issues.add("Track 2 인용 부족 (%d < %d)"
                    .formatted(report.citations().size(), MIN_CITATIONS_TRACK_2));
        }

        for (var c : report.citations()) {
            if (!policyIndex.exists(c.id())) {
                issues.add("citation id '%s' 미존재".formatted(c.id()));
            }
        }
        for (var r : report.riskFactors()) {
            if (r.citationId() != null && !policyIndex.exists(r.citationId())) {
                issues.add("riskFactor[%s] citationId '%s' 미존재"
                        .formatted(r.code(), r.citationId()));
            }
        }
        for (var s : report.strengths()) {
            if (s.citationId() != null && !policyIndex.exists(s.citationId())) {
                issues.add("strength[%s] citationId '%s' 미존재"
                        .formatted(s.code(), s.citationId()));
            }
        }

        if (issues.isEmpty()) {
            return ValidationResult.ok();
        }
        log.warn("grounding 검증 실패 track={} issues={}", report.track(), issues);
        return ValidationResult.fail(issues);
    }

    public record ValidationResult(boolean passed, List<String> issues) {

        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult fail(List<String> issues) {
            return new ValidationResult(false, List.copyOf(issues));
        }
    }
}
