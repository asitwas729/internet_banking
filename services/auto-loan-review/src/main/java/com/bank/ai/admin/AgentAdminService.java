package com.bank.ai.admin;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.agent.PreReviewAgentService;
import com.bank.ai.audit.AgentAuditRecord;
import com.bank.ai.audit.AuditLogProperties;
import com.bank.ai.audit.AuditLogService;
import com.bank.ai.drift.DriftProperties;
import com.bank.ai.llm.config.AgentProperties;
import com.bank.ai.llm.config.LlmProperties;
import com.bank.ai.llm.support.LlmRequestRateMeter;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.service.AutoReviewService;
import com.bank.ai.rule.domain.TrackDecision;
import com.bank.ai.rule.service.TrackClassifier;
import com.bank.ai.shadow.ShadowRunProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAdminService {

    private static String currentActor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.isAuthenticated()) ? auth.getName() : "anonymous";
    }

    private final AuditLogService auditLogService;
    private final AutoReviewService autoReviewService;
    private final TrackClassifier trackClassifier;
    private final PreReviewAgentService agentService;
    private final AdminActionAuditService actionAuditService;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;
    private final LlmProperties llmProperties;
    private final LlmRequestRateMeter rateMeter;
    private final AuditLogProperties auditLogProperties;
    private final ShadowRunProperties shadowRunProperties;
    private final DriftProperties driftProperties;
    private final MeterRegistry meterRegistry;

    public AgentAuditRecord getAuditLog(Long revId) {
        try {
            AgentAuditRecord record = auditLogService.findLatestByRevId(revId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "감사 로그 없음 revId=" + revId));
            actionAuditService.record(AdminActionAuditRecord.success(currentActor(), "QUERY_AUDIT_LOG", revId));
            return record;
        } catch (ResponseStatusException e) {
            actionAuditService.record(AdminActionAuditRecord.failure(currentActor(), "QUERY_AUDIT_LOG", revId, e.getReason()));
            throw e;
        }
    }

    /**
     * 감사 로그의 requestSnapshotJson 을 기반으로 에이전트 파이프라인을 재실행한다.
     * 결과는 반환만 하고 DB 에 저장하지 않는다.
     */
    public ReplayDryRunResponse replayDryRun(Long revId) {
        try {
            AgentAuditRecord record = auditLogService.findLatestByRevId(revId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "감사 로그 없음 revId=" + revId));

            AutoReviewRequest req;
            try {
                req = objectMapper.readValue(record.requestSnapshotJson(), AutoReviewRequest.class);
            } catch (Exception e) {
                log.error("dry-run 요청 역직렬화 실패 revId={}", revId, e);
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "요청 스냅샷 역직렬화 실패 revId=" + revId);
            }

            AutoReviewResponse inference = autoReviewService.review(req);
            double pd = inference.pdScore() != null
                    ? inference.pdScore()
                    : inference.proba().getOrDefault("REJECT", 0.0);
            Double decisionScore = inference.pdScore() != null ? inference.decisionScore() : null;
            TrackDecision decision = trackClassifier.classify(req, pd, decisionScore);

            AgentOpinion replayedOpinion = agentService.run(revId, req, decision);

            String replayedHash = AgentAuditRecord.sha256Hex(record.requestSnapshotJson());
            boolean hashMatch = record.inputHash() != null
                    && record.inputHash().equals(replayedHash);

            log.info("dry-run replay revId={} track={} hashMatch={}", revId, decision.track(), hashMatch);

            actionAuditService.record(AdminActionAuditRecord.success(currentActor(), "REPLAY_DRY_RUN", revId));
            return new ReplayDryRunResponse(revId, true, hashMatch,
                    record.inputHash(), replayedHash, replayedOpinion);

        } catch (ResponseStatusException e) {
            actionAuditService.record(AdminActionAuditRecord.failure(currentActor(), "REPLAY_DRY_RUN", revId, e.getReason()));
            throw e;
        }
    }

    /** 에이전트 런타임 상태 스냅샷 반환. */
    public AgentStatusResponse buildStatus() {
        actionAuditService.record(AdminActionAuditRecord.success(currentActor(), "QUERY_STATUS", null));
        return new AgentStatusResponse(
                agentProperties.enabled(),
                rateMeter.getRpmRemaining(),
                rateMeter.getRpdRemaining(),
                sumCounters("ai.agent.runs.total"),
                sumCounters("ai.agent.fallback.total"),
                sumCounters("ai.agent.disagreement.total"),
                llmProperties.model(),
                auditLogProperties.promptVersion(),
                shadowRunProperties.enabled(),
                driftProperties.enabled()
        );
    }

    private long sumCounters(String name) {
        return (long) meterRegistry.find(name).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }
}
