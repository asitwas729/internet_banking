package com.bank.ai.admin;

import com.bank.ai.audit.AgentAuditRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
