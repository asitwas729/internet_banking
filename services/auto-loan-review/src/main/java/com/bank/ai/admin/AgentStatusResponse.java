package com.bank.ai.admin;

/**
 * GET /admin/status 응답 — 에이전트 런타임 상태 스냅샷.
 *
 * @param agentEnabled          에이전트 kill switch 상태
 * @param rpmRemaining          현재 분 남은 RPM 슬롯
 * @param rpdRemaining          오늘 남은 RPD 슬롯
 * @param totalRunsSinceStart   앱 기동 이후 에이전트 실행 누적 건수
 * @param fallbacksSinceStart   앱 기동 이후 폴백 누적 건수
 * @param disagreementsSinceStart 앱 기동 이후 불일치 누적 건수
 * @param currentModel          현재 LLM 모델 식별자
 * @param currentPromptVersion  현재 시스템 프롬프트 버전 태그
 * @param shadowModeEnabled     Shadow Mode 활성 여부
 * @param driftDetectionEnabled PSI Drift 감지 활성 여부
 */
public record AgentStatusResponse(
        boolean agentEnabled,
        int rpmRemaining,
        int rpdRemaining,
        long totalRunsSinceStart,
        long fallbacksSinceStart,
        long disagreementsSinceStart,
        String currentModel,
        String currentPromptVersion,
        boolean shadowModeEnabled,
        boolean driftDetectionEnabled
) {}
