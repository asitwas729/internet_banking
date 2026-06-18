package com.bank.ai.admin;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.agent.FallbackReason;
import com.bank.ai.agent.PreReviewAgentService;
import com.bank.ai.agent.RiskLevel;
import com.bank.ai.audit.AgentAuditRecord;
import com.bank.ai.audit.AuditLogService;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.service.AutoReviewService;
import com.bank.ai.rule.domain.Track;
import com.bank.ai.rule.domain.TrackDecision;
import com.bank.ai.rule.service.TrackClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentAdminServiceTest {

    @Mock AuditLogService auditLogService;
    @Mock AutoReviewService autoReviewService;
    @Mock TrackClassifier trackClassifier;
    @Mock PreReviewAgentService agentService;
    @Mock AdminActionAuditService actionAuditService;

    private AgentAdminService service;

    @BeforeEach
    void setUp() {
        service = new AgentAdminService(
                auditLogService, autoReviewService, trackClassifier,
                agentService, actionAuditService, new ObjectMapper());
    }

    // ── getAuditLog ───────────────────────────────────────────────────────────

    @Test
    void getAuditLog_존재하는revId_레코드_반환() {
        var record = sampleRecord(101L, "TRACK_1", null);
        when(auditLogService.findLatestByRevId(101L)).thenReturn(Optional.of(record));

        AgentAuditRecord result = service.getAuditLog(101L);

        assertThat(result.revId()).isEqualTo(101L);
        assertThat(result.track()).isEqualTo("TRACK_1");
    }

    @Test
    void getAuditLog_없는revId_404_예외() {
        when(auditLogService.findLatestByRevId(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAuditLog(999L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
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
    }

    @Test
    void replayDryRun_없는revId_404_예외() {
        when(auditLogService.findLatestByRevId(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replayDryRun(999L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
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
