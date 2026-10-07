package com.netbackup.controller;

import com.netbackup.model.ConfigBackup;
import com.netbackup.model.Device;
import com.netbackup.repo.ConfigBackupRepository;
import com.netbackup.repo.DeviceRepository;
import com.netbackup.service.AuditService;
import com.netbackup.service.BackupService;
import com.netbackup.service.RiskAnalyzer;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/backups")
public class BackupController {
    private final ConfigBackupRepository repo;
    private final DeviceRepository devices;
    private final BackupService service;
    private final AuditService audit;

    public BackupController(ConfigBackupRepository repo, DeviceRepository devices, BackupService service, AuditService audit) {
        this.repo = repo;
        this.devices = devices;
        this.service = service;
        this.audit = audit;
    }

    @GetMapping
    public List<ConfigBackup> all() { return repo.findAll(); }

    @GetMapping("/{id}")
    public ConfigBackup one(@PathVariable Long id) { return service.get(id); }

    /** Compare two backups: /api/backups/compare?a=1&b=2  (response now also contains a risk rating of the change). */
    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam Long a, @RequestParam Long b) {
        ConfigBackup x = service.get(a), y = service.get(b);
        List<Map<String, String>> lines = service.diff(x.getContent(), y.getContent());
        RiskAnalyzer.Result risk = service.analyzeRisk(x.getContent(), y.getContent());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fromVersion", x.getVersion());
        out.put("toVersion", y.getVersion());
        out.put("added", lines.stream().filter(l -> l.get("type").equals("added")).count());
        out.put("removed", lines.stream().filter(l -> l.get("type").equals("removed")).count());
        out.put("riskLevel", risk.level());
        out.put("riskScore", risk.score());
        out.put("findings", risk.findings());
        out.put("lines", lines);
        String name = devices.findById(y.getDeviceId()).map(Device::getName).orElse(null);
        audit.log("COMPARE", y.getDeviceId(), name, y.getVersion(), "SUCCESS",
                "v" + x.getVersion() + " vs v" + y.getVersion() + ", risk " + risk.level());
        return out;
    }

    @PostMapping("/{id}/restore")
    public ConfigBackup restore(@PathVariable Long id) {
        ConfigBackup old = service.get(id);
        String name = devices.findById(old.getDeviceId()).map(Device::getName).orElse(null);
        try {
            ConfigBackup nb = service.restore(id);
            audit.log("RESTORE", old.getDeviceId(), name, nb.getVersion(), "SUCCESS",
                    "Restored v" + old.getVersion() + " as v" + nb.getVersion() + ", risk " + nb.getRiskLevel());
            return nb;
        } catch (ResponseStatusException e) {
            audit.log("RESTORE", old.getDeviceId(), name, old.getVersion(), "FAILED", e.getReason());
            throw e;
        }
    }
}
