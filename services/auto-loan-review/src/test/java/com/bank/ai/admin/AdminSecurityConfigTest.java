package com.bank.ai.admin;

import com.bank.ai.audit.AgentAuditRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentAdminController.class)
@Import(AdminSecurityConfig.class)
class AdminSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentAdminService adminService;

    // ── 미인증 → 401 ──────────────────────────────────────────────────────────

    @Test
    void 미인증_status_401() throws Exception {
        mockMvc.perform(get("/admin/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 미인증_audit_401() throws Exception {
        mockMvc.perform(get("/admin/audit/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 미인증_replay_401() throws Exception {
        mockMvc.perform(post("/admin/replay/1"))
                .andExpect(status().isUnauthorized());
    }

    // ── 잘못된 역할 → 403 ────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "USER")
    void USER_역할_status_403() throws Exception {
        mockMvc.perform(get("/admin/status"))
                .andExpect(status().isForbidden());
    }

    // ── AI_ADMIN 역할 → 200 ──────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "AI_ADMIN")
    void AI_ADMIN_역할_status_200() throws Exception {
        when(adminService.buildStatus()).thenReturn(
                new AgentStatusResponse(true, 15, 1500, 0L, 0L, 0L, "stub-v1", "v1", false, true));

        mockMvc.perform(get("/admin/status"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "AI_ADMIN")
    void AI_ADMIN_역할_audit_200() throws Exception {
        String requestJson = "{\"age\":35}";
        when(adminService.getAuditLog(1L)).thenReturn(
                new AgentAuditRecord(1L, "TRACK_1", requestJson,
                        "{}", "[]", null, true, null,
                        AgentAuditRecord.sha256Hex(requestJson), "stub-v1", "v1"));

        mockMvc.perform(get("/admin/audit/1"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "AI_ADMIN")
    void AI_ADMIN_역할_replay_200() throws Exception {
        String hash = AgentAuditRecord.sha256Hex("{\"age\":35}");
        when(adminService.replayDryRun(1L)).thenReturn(
                new ReplayDryRunResponse(1L, true, true, hash, hash, null));

        mockMvc.perform(post("/admin/replay/1"))
                .andExpect(status().isOk());
    }
}
