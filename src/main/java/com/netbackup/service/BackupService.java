package com.netbackup.service;

import com.netbackup.model.ConfigBackup;
import com.netbackup.model.Device;
import com.netbackup.repo.ConfigBackupRepository;
import com.netbackup.repo.DeviceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class BackupService {
    private final ConfigBackupRepository backups;
    private final DeviceRepository devices;
    private final RiskAnalyzer risk = new RiskAnalyzer();

    public BackupService(ConfigBackupRepository backups, DeviceRepository devices) {
        this.backups = backups;
        this.devices = devices;
    }

    public ConfigBackup get(Long id) {
        return backups.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Backup not found"));
    }

    /** Saves a new version. content == null means the backup failed. Successful versions get a risk rating. */
    @Transactional
    public ConfigBackup save(Device d, String content, String fileName, String source, String note) {
        ConfigBackup b = new ConfigBackup();
        b.setDeviceId(d.getId());
        b.setSource(source);
        b.setNote(note);
        b.setCreatedBy(AuditService.currentUser().equals("anonymous") ? "system" : AuditService.currentUser());
        if (content == null) {
            b.setStatus("FAILED");
            b.setVersion(0);
            b.setFileName("-");
            b.setContent("");
        } else {
            Optional<ConfigBackup> prev = backups.findFirstByDeviceIdAndStatusOrderByIdDesc(d.getId(), "SUCCESS");
            RiskAnalyzer.Result r = prev.isPresent() ? risk.analyze(prev.get().getContent(), content) : risk.baseline();
            int v = backups.maxVersion(d.getId()) + 1;
            b.setStatus("SUCCESS");
            b.setVersion(v);
            b.setContent(content);
            b.setChecksum(AuthService.sha256(content));
            b.setFileName(fileName != null ? fileName : d.getName() + "-v" + v + ".cfg");
            b.setRiskLevel(r.level());
            b.setRiskScore(r.score());
            b.setRiskDetails(r.encode());
            d.setCurrentVersion(v);
            d.setLastBackupAt(LocalDateTime.now());
            devices.save(d);
        }
        return backups.save(b);
    }

    /** Simulated backup: no real device needed. Inactive devices "time out". (unchanged behaviour) */
    public ConfigBackup simulateBackup(Device d) {
        if (!"ACTIVE".equals(d.getStatus()))
            return save(d, null, null, "SIMULATED", "Device unreachable (simulated SSH timeout)");
        return save(d, generateConfig(d, d.getCurrentVersion() + 1), null, "SIMULATED", "Backup via simulated SSH");
    }

    public ConfigBackup restore(Long backupId) {
        ConfigBackup old = get(backupId);
        if (!"SUCCESS".equals(old.getStatus()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot restore a failed backup");
        Device d = devices.findById(old.getDeviceId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        // Restore creates a NEW version with the old content, so history is never lost.
        return save(d, old.getContent(), null, "RESTORE", "Restored from version " + old.getVersion());
    }

    public List<Map<String, String>> diff(String a, String b) { return DiffUtil.diff(a, b); }

    public RiskAnalyzer.Result analyzeRisk(String from, String to) { return risk.analyze(from, to); }

    public String generateConfig(Device d, int v) { return ConfigSimulator.generate(d.getName(), d.getIpAddress(), v); }

    /** Rates versions created before the risk analyzer existed (used when upgrading from v1). */
    @Transactional
    public int backfillRisk() {
        int n = 0;
        for (Device d : devices.findAll()) {
            List<ConfigBackup> list = new ArrayList<>(backups.findByDeviceIdOrderByIdDesc(d.getId()));
            Collections.reverse(list);
            String prev = null;
            for (ConfigBackup b : list) {
                if (!"SUCCESS".equals(b.getStatus())) continue;
                if (b.getRiskLevel() == null) {
                    RiskAnalyzer.Result r = prev == null ? risk.baseline() : risk.analyze(prev, b.getContent());
                    b.setRiskLevel(r.level());
                    b.setRiskScore(r.score());
                    b.setRiskDetails(r.encode());
                    backups.save(b);
                    n++;
                }
                prev = b.getContent();
            }
        }
        return n;
    }
}
