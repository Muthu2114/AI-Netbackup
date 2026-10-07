package com.netbackup.controller;

import com.netbackup.model.ConfigBackup;
import com.netbackup.model.Device;
import com.netbackup.repo.DeviceRepository;
import com.netbackup.service.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration Assistant. GET /status: any role. POST /analyze: ADMIN or OPERATOR.
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final AiAssistantService ai;
    private final BackupService backups;
    private final DeviceRepository devices;
    private final AuditService audit;

    public AiController(AiAssistantService ai, BackupService backups, DeviceRepository devices, AuditService audit) {
        this.ai = ai;
        this.backups = backups;
        this.devices = devices;
        this.audit = audit;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", ai.isEnabled(), "model", ai.getModel(), "provider", "Local Weka Random Forest");
    }

    /** Body: {"fromId": <older backup id>, "toId": <newer backup id>} */
    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody Map<String, Object> body) {
        ConfigBackup from = backups.get(id(body.get("fromId"))), to = backups.get(id(body.get("toId")));
        Device d = devices.findById(to.getDeviceId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        if (!"SUCCESS".equals(from.getStatus()) || !"SUCCESS".equals(to.getStatus()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only successful backups can be analyzed");
        if (!from.getDeviceId().equals(to.getDeviceId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both versions must belong to the same device");
        String label = "v" + from.getVersion() + " vs v" + to.getVersion();
        try {
            boolean[] cached = new boolean[1];
            AiConfigAnalyzer.Outcome o = ai.analyze(d, from, to, cached);
            audit.log("AI_ANALYZE", d.getId(), d.getName(), to.getVersion(), "SUCCESS",
                    label + ", AI risk " + o.ai().riskLevel() + " (" + ai.getModel() + (cached[0] ? ", cached" : "") + ")");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("fromVersion", from.getVersion());
            out.put("toVersion", to.getVersion());
            out.put("riskLevel", o.ai().riskLevel());
            out.put("explanation", o.ai().explanation());
            out.put("impact", o.ai().impact());
            out.put("recommendedAction", o.ai().recommendedAction());
            out.put("keyChanges", o.ai().keyChanges());
            out.put("model", o.ai().model());
            out.put("cached", cached[0]);
            out.put("localRiskLevel", o.local().level());
            out.put("changedLines", o.changedLines());
            return out;
        } catch (AiException e) {
            audit.log("AI_ANALYZE", d.getId(), d.getName(), to.getVersion(), "FAILED", label + ": " + e.getMessage());
            throw new ResponseStatusException(HttpStatus.valueOf(e.getStatus()), e.getMessage());
        }
    }

    private static Long id(Object o) {
        try { return Long.valueOf(String.valueOf(o)); }
        catch (NumberFormatException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fromId and toId are required"); }
    }
}
