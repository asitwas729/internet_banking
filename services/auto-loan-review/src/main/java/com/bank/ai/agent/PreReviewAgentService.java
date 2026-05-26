package com.bank.ai.agent;

import com.bank.ai.llm.config.AgentProperties;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.rule.domain.TrackDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 사전 심사 에이전트 오케스트레이터 — pre-review-agent-plan.md §아키텍처.
 *
 * <p>A4 에서 Spring AI ChatClient + tool calling 로 구현.
 * 현재는 kill switch 체크만 수행하고 fallback 반환.
 *
 * <p>진입 시 {@code loan_review.agent_opinion_json IS NOT NULL} 조회 → 이미 있으면 즉시 skip
 * (멱등성) — A6 트리거 연결 시 구현.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreReviewAgentService {

    private final AgentProperties agentProps;

    /**
     * @param revId    loan_review PK (멱등성 체크 및 DB 저장용)
     * @param request  자동심사 입력 (59 필드)
     * @param decision RuleEngine 트랙 분기 결과
     * @return AgentOpinion — fallback_reason != null 이면 분석 미실행
     */
    public AgentOpinion run(Long revId, AutoReviewRequest request, TrackDecision decision) {
        if (!agentProps.enabled()) {
            log.info("PreReviewAgentService: kill switch — AGENT_DISABLED revId={}", revId);
            return AgentOpinion.fallback(FallbackReason.AGENT_DISABLED);
        }

        // A4 에서 ChatClient + tool calling 구현
        log.warn("PreReviewAgentService: A4 미구현 — AGENT_DISABLED fallback revId={}", revId);
        return AgentOpinion.fallback(FallbackReason.AGENT_DISABLED);
    }
}
