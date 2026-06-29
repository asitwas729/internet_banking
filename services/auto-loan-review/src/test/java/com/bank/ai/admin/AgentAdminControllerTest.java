package com.bank.ai.admin;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.agent.FallbackReason;
import com.bank.ai.agent.RiskLevel;
import com.bank.ai.audit.AgentAuditRecord;
import com.bank.ai.admin.AgentStatusResponse;
import com.bank.ai.drift.FairnessReport;
import com.bank.ai.drift.PsiDriftReport;
import com.bank.ai.drift.PsiStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AgentAdminControllerTest {

    @Mock
    private AgentAdminService adminService;

    @InjectMocks
    private AgentAdminController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── GET /admin/status ─────────────────────────────────────────────────────

    @Test
    void getStatus_200과_상태_스냅샷_반환() throws Exception {
        var statusResponse = new AgentStatusResponse(
                true, 12, 1480, 42L, 3L, 1L, "stub-v1", "v1", false, true);
        when(adminService.buildStatus()).thenReturn(statusResponse);

        mockMvc.perform(get("/admin/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentEnabled").value(true))
                .andExpect(jsonPath("$.rpmRemaining").value(12))
                .andExpect(jsonPath("$.rpdRemaining").value(1480))
                .andExpect(jsonPath("$.currentModel").value("stub-v1"))
                .andExpect(jsonPath("$.currentPromptVersion").value("v1"))
                .andExpect(jsonPath("$.shadowModeEnabled").value(false))
                .andExpect(jsonPath("$.driftDetectionEnabled").value(true))
                .andExpect(jsonPath("$.totalRunsSinceStart").value(42))
                .andExpect(jsonPath("$.fallbacksSinceStart").value(3));
    }

    // ── GET /admin/audit/{revId} ──────────────────────────────────────────────

    @Test
    void getAuditLog_존재하는revId_200과_감사로그_반환() throws Exception {
        String requestJson = "{\"age\":35,\"productCode\":\"MORT_001\"}";
        var record = new AgentAuditRecord(
                101L, "TRACK_3", requestJson,
                "{\"schema_version\":\"v1\",\"risk_level\":\"MEDIUM\"}",
                "[]", null, true, null,
                AgentAuditRecord.sha256Hex(requestJson), "stub-v1", "v1");

        when(adminService.getAuditLog(101L)).thenReturn(record);

        mockMvc.perform(get("/admin/audit/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revId").value(101))
                .andExpect(jsonPath("$.track").value("TRACK_3"))
                .andExpect(jsonPath("$.piiMasked").value(true))
                .andExpect(jsonPath("$.modelVersion").value("stub-v1"));
    }

    @Test
    void getAuditLog_없는revId_404_반환() throws Exception {
        when(adminService.getAuditLog(999L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "감사 로그 없음 revId=999"));

        mockMvc.perform(get("/admin/audit/999"))
                .andExpect(status().isNotFound());
    }

    // ── POST /admin/replay/{revId} ────────────────────────────────────────────

    @Test
    void replayDryRun_존재하는revId_200과_dryRun결과_반환() throws Exception {
        AgentOpinion opinion = AgentOpinion.of(
                0.72, 0.15, RiskLevel.MEDIUM, List.of(), "재현 요약", List.of(), false);
        String hash = AgentAuditRecord.sha256Hex("{\"age\":35}");
        var response = new ReplayDryRunResponse(202L, true, true, hash, hash, opinion);

        when(adminService.replayDryRun(202L)).thenReturn(response);

        mockMvc.perform(post("/admin/replay/202"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revId").value(202))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.inputHashMatch").value(true))
                .andExpect(jsonPath("$.replayedOpinion.risk_level").value("MEDIUM"));
    }

    @Test
    void replayDryRun_없는revId_404_반환() throws Exception {
        when(adminService.replayDryRun(999L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "감사 로그 없음 revId=999"));

        mockMvc.perform(post("/admin/replay/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void replayDryRun_스냅샷역직렬화실패_422_반환() throws Exception {
        when(adminService.replayDryRun(303L))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "요청 스냅샷 역직렬화 실패 revId=303"));

        mockMvc.perform(post("/admin/replay/303"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── GET /admin/drift/psi ──────────────────────────────────────────────────

    @Test
    void getPsiReport_200과_피처별_PSI_반환() throws Exception {
        var reports = List.of(
                new PsiDriftReport("creditScore", 0.05, PsiStatus.STABLE, 120, "v1"));
        when(adminService.getPsiReport()).thenReturn(reports);

        mockMvc.perform(get("/admin/drift/psi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].featureName").value("creditScore"))
                .andExpect(jsonPath("$[0].status").value("STABLE"))
                .andExpect(jsonPath("$[0].psiValue").value(0.05));
    }

    // ── GET /admin/drift/fairness ─────────────────────────────────────────────

    @Test
    void getFairnessReport_파라미터없이_200과_공정성_리포트_반환() throws Exception {
        var reports = List.of(
                new FairnessReport(LocalDate.of(2026, 5, 1), "AGE_20S",
                        0.62, 80, 0.75, 0.13, true));
        when(adminService.getFairnessReport(null)).thenReturn(reports);

        mockMvc.perform(get("/admin/drift/fairness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].groupKey").value("AGE_20S"))
                .andExpect(jsonPath("$[0].flagged").value(true));
    }

    // ── GET /admin/shadow/diverged ────────────────────────────────────────────

    @Test
    void getShadowDiverged_파라미터없이_200과_divergence_통계_반환() throws Exception {
        var response = new ShadowDivergedResponse(
                3, 20, 0.85, 0.02,
                LocalDate.of(2026, 6, 11), LocalDate.of(2026, 6, 18));
        when(adminService.getShadowDiverged(null, null)).thenReturn(response);

        mockMvc.perform(get("/admin/shadow/diverged"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.divergedCount").value(3))
                .andExpect(jsonPath("$.totalCount").value(20))
                .andExpect(jsonPath("$.agreementRate").value(0.85));
    }

    // ── POST /admin/replay/{revId}/regenerate ─────────────────────────────────

    @Test
    void regenerate_존재하는revId_200과_AgentOpinion_반환() throws Exception {
        AgentOpinion opinion = AgentOpinion.of(
                0.80, 0.10, RiskLevel.LOW, List.of(), "재생성 요약", List.of(), false);
        when(adminService.regenerateOpinion(1L, "이의신청 처리")).thenReturn(opinion);

        mockMvc.perform(post("/admin/replay/1/regenerate")
                        .contentType("application/json")
                        .content("{\"reason\":\"이의신청 처리\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risk_level").value("LOW"));
    }

    @Test
    void regenerate_reason_누락시_400() throws Exception {
        mockMvc.perform(post("/admin/replay/1/regenerate")
                        .contentType("application/json")
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void regenerate_없는revId_404() throws Exception {
        when(adminService.regenerateOpinion(999L, "test"))
                .thenThrow(new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "not found"));

        mockMvc.perform(post("/admin/replay/999/regenerate")
                        .contentType("application/json")
                        .content("{\"reason\":\"test\"}"))
                .andExpect(status().isNotFound());
    }

    // ── POST /admin/toggle ────────────────────────────────────────────────────

    @Test
    void toggle_enabled_false_200() throws Exception {
        when(adminService.toggleAgent(false))
                .thenReturn(java.util.Map.of("agentEnabled", false));

        mockMvc.perform(post("/admin/toggle").param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentEnabled").value(false));
    }

    // ── POST /admin/rate-meter/flush ──────────────────────────────────────────

    @Test
    void flushRateMeter_200과_잔여_슬롯_반환() throws Exception {
        when(adminService.flushRateMeter())
                .thenReturn(java.util.Map.of("rpmRemaining", 15, "rpdRemaining", 1500));

        mockMvc.perform(post("/admin/rate-meter/flush"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rpmRemaining").value(15));
    }
}
