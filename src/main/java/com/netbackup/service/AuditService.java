package com.netbackup.service;

import com.netbackup.model.AuditLog;
import com.netbackup.repo.AuditLogRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) { this.repo = repo; }

    public static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken) ? a.getName() : "anonymous";
    }

    /** Logs as the currently logged-in user. */
    public void log(String action, Long deviceId, String deviceName, Integer version, String status, String details) {
        logAs(currentUser(), action, deviceId, deviceName, version, status, details);
    }

    /** Logs as an explicit user (login attempts, scheduler = "SYSTEM"). Never breaks the main operation. */
    public void logAs(String user, String action, Long deviceId, String deviceName, Integer version, String status, String details) {
        try {
            AuditLog a = new AuditLog();
            a.setUsername(user);
            a.setAction(action);
            a.setDeviceId(deviceId);
            a.setDeviceName(deviceName);
            a.setVersion(version != null && version > 0 ? version : null);
            a.setStatus(status);
            a.setDetails(details != null && details.length() > 490 ? details.substring(0, 490) : details);
            repo.save(a);
        } catch (Exception e) {
            System.err.println("Audit log failed: " + e.getMessage());
        }
    }
}
