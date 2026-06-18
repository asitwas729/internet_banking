package com.bank.ai.admin;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.agent.PreReviewAgentService;
import com.bank.ai.audit.AgentAuditRecord;
import com.bank.ai.audit.AuditLogProperties;
import com.bank.ai.audit.AuditLogService;
import com.bank.ai.drift.DriftProperties;
import com.bank.ai.drift.FairnessReport;
import com.bank.ai.drift.FairnessReportRepository;
import com.bank.ai.drift.PsiDriftReport;
import com.bank.ai.drift.PsiDriftResultRepository;
import com.bank.ai.llm.config.AgentProperties;
import com.bank.ai.llm.config.LlmProperties;
import com.bank.ai.llm.support.LlmRequestRateMeter;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.service.AutoReviewService;
import com.bank.ai.rule.domain.TrackDecision;
import com.bank.ai.rule.service.TrackClassifier;
import com.bank.ai.shadow.ShadowResultRepository;
import com.bank.ai.shadow.ShadowRunProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

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
    private final PsiDriftResultRepository psiDriftResultRepository;
    private final FairnessReportRepository fairnessReportRepository;
    private final ShadowResultRepository shadowResultRepository;

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

    /** 설정된 피처별 최신 PSI 결과 조회. */
    public List<PsiDriftReport> getPsiReport() {
        try {
            List<PsiDriftReport> results = driftProperties.psiFeatures().stream()
                    .map(f -> psiDriftResultRepository.findLatest(f, driftProperties.modelVersion()))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
            actionAuditService.record(AdminActionAuditRecord.success(currentActor(), "QUERY_PSI_REPORT", null));
            return results;
        } catch (Exception e) {
            actionAuditService.record(AdminActionAuditRecord.failure(currentActor(), "QUERY_PSI_REPORT", null, e.getMessage()));
            throw e;
        }
    }

    /** 지정 월(기본: 지난 달)의 flagged 공정성 리포트 조회. */
    public List<FairnessReport> getFairnessReport(@Nullable String monthParam) {
        try {
            LocalDate month = monthParam != null
                    ? YearMonth.parse(monthParam).atDay(1)
                    : YearMonth.now().minusMonths(1).atDay(1);
            List<FairnessReport> results = fairnessReportRepository.findFlaggedByMonth(month);
            actionAuditService.record(AdminActionAuditRecord.success(currentActor(), "QUERY_FAIRNESS_REPORT", null));
            return results;
        } catch (Exception e) {
            actionAuditService.record(AdminActionAuditRecord.failure(currentActor(), "QUERY_FAIRNESS_REPORT", null, e.getMessage()));
            throw e;
        }
    }

    /** 기간 내(기본: 최근 7일) shadow divergence 통계 조회. */
    public ShadowDivergedResponse getShadowDiverged(@Nullable String fromParam, @Nullable String toParam) {
        try {
            LocalDate to   = toParam   != null ? LocalDate.parse(toParam)   : LocalDate.now();
            LocalDate from = fromParam != null ? LocalDate.parse(fromParam) : to.minusDays(7);
            ShadowDivergedResponse response = new ShadowDivergedResponse(
                    shadowResultRepository.countDiverged(from, to),
                    shadowResultRepository.totalCount(from, to),
                    shadowResultRepository.agreementRate(from, to),
                    shadowResultRepository.citationMissRate(from, to),
                    from, to
            );
            actionAuditService.record(AdminActionAuditRecord.success(currentActor(), "QUERY_SHADOW_DIVERGED", null));
            return response;
        } catch (Exception e) {
            actionAuditService.record(AdminActionAuditRecord.failure(currentActor(), "QUERY_SHADOW_DIVERGED", null, e.getMessage()));
            throw e;
        }
    }

    private long sumCounters(String name) {
        return (long) meterRegistry.find(name).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }
}
