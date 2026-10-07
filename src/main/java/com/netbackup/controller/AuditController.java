package com.netbackup.controller;

import com.netbackup.model.AuditLog;
import com.netbackup.repo.AuditLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Audit log viewer - ADMIN only. Filters are optional: /api/audit?user=admin&action=BACKUP&status=FAILED&limit=100 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditLogRepository repo;

    public AuditController(AuditLogRepository repo) { this.repo = repo; }

    @GetMapping
    public List<AuditLog> search(@RequestParam(required = false) String user,
                                 @RequestParam(required = false) String action,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(defaultValue = "200") int limit) {
        String u = user == null || user.isBlank() ? null : user.trim();
        String a = action == null || action.isBlank() ? null : action.trim();
        String s = status == null || status.isBlank() ? null : status.trim();
        return repo.search(u, a, s, PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }
}
