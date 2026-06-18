package com.bank.ai.audit;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.review.event.AutoReviewEvaluatedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 에이전트 감사 로그 단건 레코드 — {@code agent_audit_log} 테이블 대응.
 *
 * <p>INSERT-ONLY 정책: 생성 후 수정·삭제 불가 (여신전문금융업법 §52의2).
 *
 * @param revId               심사 신청 ID (loan_review.id 참조)
 * @param track               TRACK_1 / TRACK_2 / TRACK_3
 * @param requestSnapshotJson PII 마스킹 완료된 AutoReviewRequest JSON
 * @param opinionJson         AgentOpinion JSON
 * @param toolCallsJson       도구 호출 이력 JSON (현 단계 빈 배열)
 * @param rawLlmResponse      LLM 원문 응답 (ai.audit.include-raw-llm-response=true 시만 채움)
 * @param piiMasked           PII 마스킹 여부 (항상 true — PiiMaskingFilter 보장)
 * @param fallbackReason      폴백 사유 코드 (정상 실행 시 null)
 * @param inputHash           SHA-256(requestSnapshotJson, UTF-8) hex 64자 — replay 재현성 검증용
 * @param modelVersion        결정 시점 LLM/스코어 모델 식별자 (ai.llm.model)
 * @param promptVersion       결정 시점 시스템 프롬프트 버전 태그 (ai.audit.prompt-version)
 */
public record AgentAuditRecord(
        Long revId,
        String track,
        String requestSnapshotJson,
        String opinionJson,
        String toolCallsJson,
        String rawLlmResponse,
        boolean piiMasked,
        String fallbackReason,
        String inputHash,
        String modelVersion,
        String promptVersion
) {

    /**
     * 이벤트 + 에이전트 의견으로부터 감사 레코드를 생성한다.
     *
     * <p>직렬화 실패 시 {@code "{}"}로 대체 (감사 로그 실패가 파이프라인을 중단하면 안 됨).
     *
     * @param modelVersion  ai.llm.model 값 — LLM/스코어 모델 식별자
     * @param promptVersion ai.audit.prompt-version 값 — 현재 배포 프롬프트 버전
     */
    public static AgentAuditRecord from(AutoReviewEvaluatedEvent event,
                                        AgentOpinion opinion,
                                        ObjectMapper mapper,
                                        boolean includeRawLlmResponse,
                                        String modelVersion,
                                        String promptVersion) {
        String requestJson = safeSerialize(event.request(), mapper, "{}");
        String opinionJson = safeSerialize(opinion, mapper, "{}");
        String fallbackReason = opinion.fallbackReason() != null
                ? opinion.fallbackReason().name()
                : null;

        return new AgentAuditRecord(
                event.revId(),
                event.decision().track().name(),
                requestJson,
                opinionJson,
                "[]",
                includeRawLlmResponse ? null : null,
                true,
                fallbackReason,
                sha256Hex(requestJson),
                modelVersion,
                promptVersion
        );
    }

    @Slf4j
    private static final class LogHolder {}

    /** SHA-256(input, UTF-8) → 소문자 hex 64자. SHA-256 은 JVM 표준 보장이므로 예외 도달 불가. */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String safeSerialize(Object obj, ObjectMapper mapper, String fallback) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return fallback;
        }
    }
}
