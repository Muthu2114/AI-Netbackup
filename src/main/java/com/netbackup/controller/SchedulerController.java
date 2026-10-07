package com.netbackup.controller;

import com.netbackup.service.AuditService;
import com.netbackup.service.ScheduledBackupService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** ADMIN only. */
@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {
    private final ScheduledBackupService scheduler;

    public SchedulerController(ScheduledBackupService scheduler) { this.scheduler = scheduler; }

    @GetMapping
    public Map<String, Object> status() { return scheduler.status(); }

    @PostMapping("/run")
    public Map<String, Object> runNow() { return scheduler.runAll(AuditService.currentUser()); }
}
