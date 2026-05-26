package com.bank.ai.llm.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 인라인 정책 텍스트 인덱스 — plan/llm-pipeline.md §14.
 *
 * <p>application.yml {@code ai.policy.inline} 섹션 바인딩. citation id → policy entry 룩업.
 *
 * <p>Phase 1.7 RAG 도입 시 본 인덱스 의미가 PolicyChunk vector store id 로 swap.
 * 인터페이스 (exists / get) 는 비변경 — ReviewReportService·GroundingValidator 호환.
 */
@ConfigurationProperties(prefix = "ai.policy")
public record PolicyIndex(Map<String, PolicyEntry> inline) {

    public PolicyIndex {
        inline = inline != null ? Map.copyOf(inline) : Map.of();
    }

    public boolean exists(String id) {
        return inline.containsKey(id);
    }

    public PolicyEntry get(String id) {
        return inline.get(id);
    }

    /**
     * @param text   심사원·LLM 노출용 정책 본문
     * @param source 출처 식별자 (예: "internal_policy_2026q2")
     */
    public record PolicyEntry(String text, String source) {

        public PolicyEntry {
            text = text != null ? text : "";
            source = source != null ? source : "";
        }
    }
}
