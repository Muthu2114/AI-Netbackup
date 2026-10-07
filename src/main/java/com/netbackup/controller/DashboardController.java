package com.netbackup.controller;

import com.netbackup.model.ConfigBackup;
import com.netbackup.model.Device;
import com.netbackup.repo.AuditLogRepository;
import com.netbackup.repo.ConfigBackupRepository;
import com.netbackup.repo.DeviceRepository;
import com.netbackup.service.ScheduledBackupService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DeviceRepository devices;
    private final ConfigBackupRepository backups;
    private final AuditLogRepository auditRepo;
    private final ScheduledBackupService scheduler;

    public DashboardController(DeviceRepository devices, ConfigBackupRepository backups,
                               AuditLogRepository auditRepo, ScheduledBackupService scheduler) {
        this.devices = devices;
        this.backups = backups;
        this.auditRepo = auditRepo;
        this.scheduler = scheduler;
    }

    @GetMapping
    public Map<String, Object> stats(Authentication auth) {
        Map<String, Object> m = new LinkedHashMap<>();
        // ---- original v1 fields (unchanged) ----
        m.put("totalDevices", devices.count());
        m.put("activeDevices", devices.countByStatus("ACTIVE"));
        m.put("totalBackups", backups.count());
        m.put("totalVersions", backups.countByStatus("SUCCESS"));
        m.put("successBackups", backups.countByStatus("SUCCESS"));
        m.put("failedBackups", backups.countByStatus("FAILED"));
        m.put("recent", backups.findTop8ByOrderByIdDesc());

        // ---- v2 additions ----
        Map<String, Long> risk = new LinkedHashMap<>();
        for (String l : List.of("LOW", "MEDIUM", "HIGH")) risk.put(l, backups.countByRiskLevel(l));
        m.put("riskDistribution", risk);

        List<Map<String, Object>> deviceRisk = new ArrayList<>();
        Map<String, Long> byType = new LinkedHashMap<>();
        for (Device d : devices.findAll()) {
            byType.merge(d.getType() == null ? "Other" : d.getType(), 1L, Long::sum);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("deviceId", d.getId());
            r.put("name", d.getName());
            Optional<ConfigBackup> b = backups.findFirstByDeviceIdAndStatusOrderByIdDesc(d.getId(), "SUCCESS");
            r.put("version", b.map(ConfigBackup::getVersion).orElse(null));
            r.put("riskLevel", b.map(ConfigBackup::getRiskLevel).orElse(null));
            r.put("riskScore", b.map(ConfigBackup::getRiskScore).orElse(null));
            deviceRisk.add(r);
        }
        m.put("deviceRisk", deviceRisk);
        m.put("devicesByType", byType);

        LocalDate today = LocalDate.now();
        Map<LocalDate, long[]> perDay = new TreeMap<>();
        for (int i = 6; i >= 0; i--) perDay.put(today.minusDays(i), new long[2]);
        for (ConfigBackup b : backups.findByCreatedAtAfter(today.minusDays(6).atStartOfDay())) {
            long[] c = perDay.get(b.getCreatedAt().toLocalDate());
            if (c != null) c["SUCCESS".equals(b.getStatus()) ? 0 : 1]++;
        }
        List<Map<String, Object>> days = new ArrayList<>();
        perDay.forEach((day, c) -> days.add(Map.of("date", day.toString(), "success", c[0], "failed", c[1])));
        m.put("backupsByDay", days);

        m.put("scheduler", scheduler.status());
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        m.put("recentActivity", admin ? auditRepo.findTop8ByOrderByIdDesc() : List.of());   // audit data is admin-only
        return m;
    }
}
