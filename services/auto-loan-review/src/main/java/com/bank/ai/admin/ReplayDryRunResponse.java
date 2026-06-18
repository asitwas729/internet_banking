package com.bank.ai.admin;

import com.bank.ai.agent.AgentOpinion;

/**
 * POST /admin/replay/{revId} dry-run 결과.
 *
 * @param revId             재현 대상 심사 ID
 * @param dryRun            항상 true — 결과를 DB에 저장하지 않음
 * @param inputHashMatch    원본 audit 로그의 input_hash 와 replay 시 재계산 hash 의 일치 여부
 * @param originalInputHash 원본 감사 로그에 저장된 SHA-256 hex
 * @param replayedInputHash replay 시 requestSnapshotJson 으로 재계산한 SHA-256 hex
 * @param replayedOpinion   에이전트 재실행 결과 (저장하지 않음)
 */
public record ReplayDryRunResponse(
        Long revId,
        boolean dryRun,
        boolean inputHashMatch,
        String originalInputHash,
        String replayedInputHash,
        AgentOpinion replayedOpinion
) {}
