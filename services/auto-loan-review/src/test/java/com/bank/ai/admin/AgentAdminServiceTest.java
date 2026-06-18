package com.bank.ai.admin;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.agent.FallbackReason;
import com.bank.ai.agent.PreReviewAgentService;
import com.bank.ai.agent.RiskLevel;
import com.bank.ai.audit.AgentAuditRecord;
import com.bank.ai.audit.AuditLogProperties;
import com.bank.ai.audit.AuditLogService;
import com.bank.ai.drift.DriftProperties;
import com.bank.ai.drift.FairnessReport;
import com.bank.ai.drift.FairnessReportRepository;
import com.bank.ai.drift.PsiDriftReport;
import com.bank.ai.drift.PsiDriftResultRepository;
import com.bank.ai.drift.PsiStatus;
import com.bank.ai.llm.config.AgentProperties;
import com.bank.ai.llm.config.LlmProperties;
import com.bank.ai.llm.support.LlmRequestRateMeter;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.service.AutoReviewService;
import com.bank.ai.rule.domain.Track;
import com.bank.ai.rule.domain.TrackDecision;
import com.bank.ai.rule.service.TrackClassifier;
import com.bank.ai.shadow.ShadowResultRepository;
import com.bank.ai.shadow.ShadowRunProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bank.ai.admin.AgentStatusResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentAdminServiceTest {

    @Mock AuditLogService auditLogService;
    @Mock AutoReviewService autoReviewService;
    @Mock TrackClassifier trackClassifier;
    @Mock PreReviewAgentService agentService;
    @Mock AdminActionAuditService actionAuditService;
    @Mock AgentProperties agentProperties;
    @Mock LlmProperties llmProperties;
    @Mock LlmRequestRateMeter rateMeter;
    @Mock AuditLogProperties auditLogProperties;
    @Mock ShadowRunProperties shadowRunProperties;
    @Mock DriftProperties driftProperties;
    @Mock PsiDriftResultRepository psiDriftResultRepository;
    @Mock FairnessReportRepository fairnessReportRepository;
    @Mock ShadowResultRepository shadowResultRepository;
    @Spy  MeterRegistry meterRegistry = new SimpleMeterRegistry();
    @Spy  ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    AgentAdminService service;

    // ── buildStatus ──────────────────────────────────────────────────────────

    @Test
    void buildStatus_에이전트_상태_스냅샷_반환() {
        when(agentProperties.enabled()).thenReturn(true);
        when(rateMeter.getRpmRemaining()).thenReturn(13);
        when(rateMeter.getRpdRemaining()).thenReturn(1450);
        when(llmProperties.model()).thenReturn("gemini-2.5-flash");
        when(auditLogProperties.promptVersion()).thenReturn("v2");
        when(shadowRunProperties.enabled()).thenReturn(false);
        when(driftProperties.enabled()).thenReturn(true);

        AgentStatusResponse result = service.buildStatus();

        assertThat(result.agentEnabled()).isTrue();
        assertThat(result.rpmRemaining()).isEqualTo(13);
        assertThat(result.rpdRemaining()).isEqualTo(1450);
        assertThat(result.currentModel()).isEqualTo("gemini-2.5-flash");
        assertThat(result.currentPromptVersion()).isEqualTo("v2");
        assertThat(result.shadowModeEnabled()).isFalse();
        assertThat(result.driftDetectionEnabled()).isTrue();
        assertThat(result.totalRunsSinceStart()).isZero();
        verify(actionAuditService).record(
                argThat(r -> "QUERY_STATUS".equals(r.action()) && "SUCCESS".equals(r.result())));
    }

    // ── getAuditLog ───────────────────────────────────────────────────────────

    @Test
    void getAuditLog_존재하는revId_레코드_반환() {
        var record = sampleRecord(101L, "TRACK_1", null);
        when(auditLogService.findLatestByRevId(101L)).thenReturn(Optional.of(record));

        AgentAuditRecord result = service.getAuditLog(101L);

        assertThat(result.revId()).isEqualTo(101L);
        assertThat(result.track()).isEqualTo("TRACK_1");
        verify(actionAuditService).record(
                argThat(r -> "QUERY_AUDIT_LOG".equals(r.action()) && "SUCCESS".equals(r.result())));
    }

    @Test
    void getAuditLog_없는revId_404_예외_그리고_FAILURE_감사기록() {
        when(auditLogService.findLatestByRevId(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAuditLog(999L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verify(actionAuditService).record(
                argThat(r -> "QUERY_AUDIT_LOG".equals(r.action()) && "FAILURE".equals(r.result())));
    }

    // ── replayDryRun ──────────────────────────────────────────────────────────

    @Test
    void replayDryRun_동일_스냅샷_hashMatch_true() {
        String requestJson = "{\"age\":35,\"productCode\":\"MORT_001\"}";
        String hash = AgentAuditRecord.sha256Hex(requestJson);
        var record = new AgentAuditRecord(
                202L, "TRACK_3", requestJson,
                "{\"schema_version\":\"v1\"}", "[]", null, true, null,
                hash, "stub-v1", "v1");

        when(auditLogService.findLatestByRevId(202L)).thenReturn(Optional.of(record));
        when(autoReviewService.review(any())).thenReturn(
                new AutoReviewResponse("stub-v1", "APPROVE", 0.8,
                        Map.of("APPROVE", 0.8, "REJECT", 0.2), 0.12, "pd-v1"));
        TrackDecision decision = new TrackDecision(
                Track.TRACK_3, List.of(), 0.12, 0.8, 0.5, 0.3, "회색지대");
        when(trackClassifier.classify(any(), anyDouble(), any())).thenReturn(decision);
        when(agentService.run(anyLong(), any(), any())).thenReturn(
                AgentOpinion.of(0.8, 0.12, RiskLevel.LOW, List.of(), "재현 요약", List.of(), false));

        ReplayDryRunResponse result = service.replayDryRun(202L);

        assertThat(result.revId()).isEqualTo(202L);
        assertThat(result.dryRun()).isTrue();
        assertThat(result.inputHashMatch()).isTrue();
        assertThat(result.originalInputHash()).isEqualTo(hash);
        assertThat(result.replayedInputHash()).isEqualTo(hash);
        assertThat(result.replayedOpinion().fallbackReason()).isNull();
        verify(actionAuditService).record(
                argThat(r -> "REPLAY_DRY_RUN".equals(r.action()) && "SUCCESS".equals(r.result())));
    }

    @Test
    void replayDryRun_없는revId_404_예외_그리고_FAILURE_감사기록() {
        when(auditLogService.findLatestByRevId(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replayDryRun(999L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verify(actionAuditService).record(
                argThat(r -> "REPLAY_DRY_RUN".equals(r.action()) && "FAILURE".equals(r.result())));
    }

    @Test
    void replayDryRun_잘못된_스냅샷Json_422_예외() {
        var record = new AgentAuditRecord(
                303L, "TRACK_1", "NOT_VALID_JSON",
                "{}", "[]", null, true, null, null, "stub-v1", "v1");
        when(auditLogService.findLatestByRevId(303L)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.replayDryRun(303L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void replayDryRun_inputHash_null인_레코드_hashMatch_false() {
        String requestJson = "{\"age\":30}";
        var record = new AgentAuditRecord(
                404L, "TRACK_1", requestJson,
                "{}", "[]", null, true, null,
                null, "stub-v1", "v1");

        when(auditLogService.findLatestByRevId(404L)).thenReturn(Optional.of(record));
        when(autoReviewService.review(any())).thenReturn(
                new AutoReviewResponse("stub-v1", "APPROVE", 0.9,
                        Map.of("APPROVE", 0.9, "REJECT", 0.1), null, null));
        TrackDecision decision = new TrackDecision(
                Track.TRACK_1, List.of(), 0.1, null, 0.5, 0.3, "자동 승인");
        when(trackClassifier.classify(any(), anyDouble(), any())).thenReturn(decision);
        when(agentService.run(anyLong(), any(), any())).thenReturn(
                AgentOpinion.fallback(FallbackReason.AGENT_DISABLED));

        ReplayDryRunResponse result = service.replayDryRun(404L);

        assertThat(result.inputHashMatch()).isFalse();
        assertThat(result.originalInputHash()).isNull();
    }

    // ── getPsiReport ─────────────────────────────────────────────────────────

    @Test
    void getPsiReport_피처목록_기반_최신PSI_반환() {
        when(driftProperties.psiFeatures()).thenReturn(List.of("creditScore"));
        when(driftProperties.modelVersion()).thenReturn("v1");
        when(psiDriftResultRepository.findLatest("creditScore", "v1"))
                .thenReturn(Optional.of(new PsiDriftReport("creditScore", 0.07, PsiStatus.STABLE, 100, "v1")));

        List<PsiDriftReport> result = service.getPsiReport();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).featureName()).isEqualTo("creditScore");
        assertThat(result.get(0).status()).isEqualTo(PsiStatus.STABLE);
        verify(actionAuditService).record(
                argThat(r -> "QUERY_PSI_REPORT".equals(r.action()) && "SUCCESS".equals(r.result())));
    }

    // ── getFairnessReport ─────────────────────────────────────────────────────

    @Test
    void getFairnessReport_월파라미터없이_지난달_조회() {
        var report = new FairnessReport(
                LocalDate.of(2026, 5, 1), "AGE_20S", 0.62, 80, 0.75, 0.13, true);
        when(fairnessReportRepository.findFlaggedByMonth(any())).thenReturn(List.of(report));

        List<FairnessReport> result = service.getFairnessReport(null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).flagged()).isTrue();
        verify(actionAuditService).record(
                argThat(r -> "QUERY_FAIRNESS_REPORT".equals(r.action()) && "SUCCESS".equals(r.result())));
    }

    @Test
    void getFairnessReport_월파라미터_지정시_해당월_조회() {
        when(fairnessReportRepository.findFlaggedByMonth(LocalDate.of(2026, 4, 1)))
                .thenReturn(List.of());

        List<FairnessReport> result = service.getFairnessReport("2026-04");

        assertThat(result).isEmpty();
    }

    // ── getShadowDiverged ────────────────────────────────────────────────────

    @Test
    void getShadowDiverged_파라미터없이_최근7일_통계_반환() {
        when(shadowResultRepository.countDiverged(any(), any())).thenReturn(2);
        when(shadowResultRepository.totalCount(any(), any())).thenReturn(10);
        when(shadowResultRepository.agreementRate(any(), any())).thenReturn(0.80);
        when(shadowResultRepository.citationMissRate(any(), any())).thenReturn(0.0);

        ShadowDivergedResponse result = service.getShadowDiverged(null, null);

        assertThat(result.divergedCount()).isEqualTo(2);
        assertThat(result.totalCount()).isEqualTo(10);
        assertThat(result.agreementRate()).isEqualTo(0.80);
        verify(actionAuditService).record(
                argThat(r -> "QUERY_SHADOW_DIVERGED".equals(r.action()) && "SUCCESS".equals(r.result())));
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private static AgentAuditRecord sampleRecord(Long revId, String track, String fallbackReason) {
        String requestJson = "{\"age\":35,\"productCode\":\"MORT_001\"}";
        return new AgentAuditRecord(
                revId, track, requestJson,
                "{\"schema_version\":\"v1\",\"risk_level\":\"LOW\"}",
                "[]", null, true, fallbackReason,
                AgentAuditRecord.sha256Hex(requestJson), "stub-v1", "v1");
    }
}
