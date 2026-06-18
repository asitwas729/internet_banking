package com.bank.ai.admin;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.audit.AgentAuditRecord;
import com.bank.ai.drift.FairnessReport;
import com.bank.ai.drift.PsiDriftReport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AgentAdminController {

    private final AgentAdminService adminService;

    @GetMapping("/status")
    public AgentStatusResponse getStatus() {
        return adminService.buildStatus();
    }

    @GetMapping("/audit/{revId}")
    public AgentAuditRecord getAuditLog(@PathVariable Long revId) {
        return adminService.getAuditLog(revId);
    }

    @PostMapping("/replay/{revId}")
    public ReplayDryRunResponse replayDryRun(@PathVariable Long revId) {
        return adminService.replayDryRun(revId);
    }

    @GetMapping("/drift/psi")
    public List<PsiDriftReport> getPsiReport() {
        return adminService.getPsiReport();
    }

    @GetMapping("/drift/fairness")
    public List<FairnessReport> getFairnessReport(
            @RequestParam(required = false) @Nullable String month) {
        return adminService.getFairnessReport(month);
    }

    @GetMapping("/shadow/diverged")
    public ShadowDivergedResponse getShadowDiverged(
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to) {
        return adminService.getShadowDiverged(from, to);
    }

    @PostMapping("/replay/{revId}/regenerate")
    public AgentOpinion regenerate(
            @PathVariable Long revId,
            @RequestBody @Valid RegenerateOpinionRequest request) {
        return adminService.regenerateOpinion(revId, request.reason());
    }

    @PostMapping("/toggle")
    public Map<String, Object> toggle(@RequestParam boolean enabled) {
        return adminService.toggleAgent(enabled);
    }

    @PostMapping("/rate-meter/flush")
    public Map<String, Object> flushRateMeter() {
        return adminService.flushRateMeter();
    }
}
