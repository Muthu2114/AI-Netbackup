package com.netbackup.service;

import com.netbackup.model.ConfigBackup;
import com.netbackup.model.Device;
import com.netbackup.repo.ConfigBackupRepository;
import com.netbackup.repo.DeviceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Automatic backups. Runs on the cron in application.properties and can be triggered by an admin.
 * Simulation: ~40% of devices "drift" (someone changed the config). Unchanged devices get no new version.
 */
@Service
public class ScheduledBackupService {
    private final DeviceRepository devices;
    private final ConfigBackupRepository backups;
    private final BackupService service;
    private final AuditService audit;
    private final boolean enabled;
    private final String cron;
    private final Random random = new Random();

    private volatile LocalDateTime lastRunAt;
    private volatile String lastRunSummary = "Not run yet";

    public ScheduledBackupService(DeviceRepository devices, ConfigBackupRepository backups, BackupService service,
                                  AuditService audit,
                                  @Value("${netbackup.schedule.enabled:true}") boolean enabled,
                                  @Value("${netbackup.schedule.cron:0 0 2 * * *}") String cron) {
        this.devices = devices;
        this.backups = backups;
        this.service = service;
        this.audit = audit;
        this.enabled = enabled;
        this.cron = cron;
    }

    @Scheduled(cron = "${netbackup.schedule.cron:0 0 2 * * *}")
    public void scheduledRun() {
        if (enabled) runAll("SYSTEM");
    }

    public synchronized Map<String, Object> runAll(String triggeredBy) {
        int newVersions = 0, unchanged = 0, failed = 0, skipped = 0;
        for (Device d : devices.findAll()) {
            if (Boolean.FALSE.equals(d.getAutoBackup())) { skipped++; continue; }
            ConfigBackup b = backupOne(d);
            if (b == null) {
                unchanged++;
                audit.logAs("SYSTEM", "BACKUP_SCHEDULED", d.getId(), d.getName(), d.getCurrentVersion(), "SUCCESS",
                        "No changes detected - no new version");
            } else if ("FAILED".equals(b.getStatus())) {
                failed++;
                audit.logAs("SYSTEM", "BACKUP_SCHEDULED", d.getId(), d.getName(), null, "FAILED", b.getNote());
            } else {
                newVersions++;
                audit.logAs("SYSTEM", "BACKUP_SCHEDULED", d.getId(), d.getName(), b.getVersion(), "SUCCESS",
                        "Change detected, risk " + b.getRiskLevel());
            }
        }
        lastRunAt = LocalDateTime.now();
        lastRunSummary = newVersions + " new version(s), " + unchanged + " unchanged, " + failed + " failed, " + skipped + " skipped";
        audit.logAs(triggeredBy, "SCHEDULER_RUN", null, null, null, "SUCCESS", lastRunSummary);
        Map<String, Object> m = new LinkedHashMap<>(status());
        m.put("newVersions", newVersions);
        m.put("unchanged", unchanged);
        m.put("failed", failed);
        return m;
    }

    /** Returns the saved backup, or null when the device config did not change. */
    private ConfigBackup backupOne(Device d) {
        if (!"ACTIVE".equals(d.getStatus()))
            return service.save(d, null, null, "SCHEDULED", "Scheduled backup failed: device unreachable");
        var latest = backups.findFirstByDeviceIdAndStatusOrderByIdDesc(d.getId(), "SUCCESS");
        if (latest.isEmpty())
            return service.save(d, service.generateConfig(d, 1), null, "SCHEDULED", "Initial scheduled backup");
        String current = latest.get().getContent();
        String fetched = ConfigSimulator.drift(current, random);
        if (fetched.equals(current)) return null;
        return service.save(d, fetched, null, "SCHEDULED", "Scheduled backup - change detected");
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("cron", cron);
        m.put("lastRunAt", lastRunAt);
        m.put("lastRunSummary", lastRunSummary);
        return m;
    }
}
