package com.bank.ai.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 에이전트 도구 콜백 레지스트리 — pre-review-agent-plan.md §A3.
 *
 * <p>A3 에서 4개 read-only tool (RecomputeWithTermsTool, PolicyFlagTool,
 * PurposeAnalysisTool, PolicyLookupTool) 을 등록. 현재는 빈 골격.
 */
@Slf4j
@Component
public class AgentToolRegistry {

    // A3: MethodToolCallbackProvider + @Tool 메서드 등록
}
