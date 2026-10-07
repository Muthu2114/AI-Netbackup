package com.netbackup.controller;

import com.netbackup.model.ConfigBackup;
import com.netbackup.model.Device;
import com.netbackup.repo.ConfigBackupRepository;
import com.netbackup.repo.DeviceRepository;
import com.netbackup.service.AuditService;
import com.netbackup.service.BackupService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Roles: GET = any role, create/update/backup/upload = ADMIN or OPERATOR, DELETE = ADMIN (see SecurityConfig). */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {
    private final DeviceRepository devices;
    private final ConfigBackupRepository backupRepo;
    private final BackupService service;
    private final AuditService audit;

    public DeviceController(DeviceRepository devices, ConfigBackupRepository backupRepo, BackupService service, AuditService audit) {
        this.devices = devices;
        this.backupRepo = backupRepo;
        this.service = service;
        this.audit = audit;
    }

    private Device find(Long id) {
        return devices.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
    }

    @GetMapping
    public List<Device> list() { return devices.findAll(); }

    @GetMapping("/{id}")
    public Device get(@PathVariable Long id) { return find(id); }

    @PostMapping
    public Device create(@RequestBody Device d) {
        if (d.getName() == null || d.getName().isBlank() || d.getIpAddress() == null || d.getIpAddress().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name and IP address are required");
        d.setId(null);
        d.setCurrentVersion(0);
        d.setLastBackupAt(null);
        if (d.getAutoBackup() == null) d.setAutoBackup(true);
        d = devices.save(d);
        audit.log("DEVICE_CREATE", d.getId(), d.getName(), null, "SUCCESS", d.getIpAddress());
        return d;
    }

    @PutMapping("/{id}")
    public Device update(@PathVariable Long id, @RequestBody Device in) {
        Device d = find(id);
        d.setName(in.getName());
        d.setIpAddress(in.getIpAddress());
        d.setType(in.getType());
        d.setVendor(in.getVendor());
        d.setLocation(in.getLocation());
        d.setStatus(in.getStatus());
        if (in.getAutoBackup() != null) d.setAutoBackup(in.getAutoBackup());
        d = devices.save(d);
        audit.log("DEVICE_UPDATE", d.getId(), d.getName(), null, "SUCCESS", "Status " + d.getStatus() + ", auto-backup " + d.getAutoBackup());
        return d;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        Device d = find(id);
        backupRepo.deleteByDeviceId(id);
        devices.deleteById(id);
        audit.log("DEVICE_DELETE", id, d.getName(), null, "SUCCESS", "Device and all versions deleted");
    }

    @GetMapping("/{id}/backups")
    public List<ConfigBackup> history(@PathVariable Long id) {
        find(id);
        return backupRepo.findByDeviceIdOrderByIdDesc(id);
    }

    /** Simulated backup - generates a config as if pulled from the device. */
    @PostMapping("/{id}/backup")
    public ConfigBackup backup(@PathVariable Long id) {
        Device d = find(id);
        ConfigBackup b = service.simulateBackup(d);
        boolean ok = "SUCCESS".equals(b.getStatus());
        audit.log("BACKUP", d.getId(), d.getName(), b.getVersion(), b.getStatus(),
                ok ? "Risk " + b.getRiskLevel() : b.getNote());
        return b;
    }

    /** Upload a real .cfg/.txt file as a new version. */
    @PostMapping("/{id}/upload")
    public ConfigBackup upload(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        Device d = find(id);
        if (file.isEmpty()) {
            audit.log("UPLOAD", d.getId(), d.getName(), null, "FAILED", "File is empty");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        ConfigBackup b = service.save(d, text, file.getOriginalFilename(), "UPLOAD", "Uploaded configuration file");
        audit.log("UPLOAD", d.getId(), d.getName(), b.getVersion(), "SUCCESS", file.getOriginalFilename() + ", risk " + b.getRiskLevel());
        return b;
    }
}
