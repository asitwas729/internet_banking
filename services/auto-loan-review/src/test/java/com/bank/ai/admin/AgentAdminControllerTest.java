package com.bank.ai.admin;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.agent.FallbackReason;
import com.bank.ai.agent.RiskLevel;
import com.bank.ai.audit.AgentAuditRecord;
import com.bank.ai.admin.AgentStatusResponse;
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
}
