package com.bank.ai.llm.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * LLM 파이프라인 정책 매개변수 — application.yml {@code ai.llm} 섹션과 바인딩.
 *
 * <p>plan/llm-pipeline.md §0·§9 의 운영 원칙:
 * <ul>
 *   <li>{@code enabled} — kill switch. false 면 모든 LLM 호출이 즉시 {@code TemplateFallback} 으로 우회.
 *       1.9.2 의 rule-engine kill switch 와 동일 패턴 (둘은 독립적으로 끌 수 있어야 함).</li>
 *   <li>{@code provider} — stub(테스트·결정론) / vertex / anthropic. Provider 추가 시 본 enum 확장.</li>
 *   <li>{@code maxTokens} / {@code temperature} — 모든 prompt 의 기본값. 개별 prompt YAML 에서 override 가능.</li>
 *   <li>{@code dailyTokenCap} — 일일 input + output token 합산 cap. 초과 시 {@code LlmCostExceededException}
 *       (후속 phase). 현 단계는 메트릭만 보유.</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "ai.llm")
public record LlmProperties(
        boolean enabled,
        @NotNull Provider provider,
        @NotBlank String model,
        @Min(1) int maxTokens,
        double temperature,
        @Min(0) long dailyTokenCap
) {

    public enum Provider {
        /** 결정론적 stub — 외부 API 호출 없이 promptId + input hash 기반 응답. 테스트·로컬 PoC. */
        STUB,
        /** Google Vertex AI (Gemini). 운영 PoC. */
        VERTEX,
        /** Anthropic Claude. 운영 cutover. */
        ANTHROPIC
    }
}
