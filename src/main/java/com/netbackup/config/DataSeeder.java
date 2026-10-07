package com.netbackup.config;

import com.netbackup.model.AppUser;
import com.netbackup.model.Device;
import com.netbackup.repo.AppUserRepository;
import com.netbackup.repo.DeviceRepository;
import com.netbackup.service.AuditService;
import com.netbackup.service.AuthService;
import com.netbackup.service.BackupService;
import com.netbackup.service.ConfigSimulator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** Runs at startup: creates users, upgrades a v1 database, rates old versions, inserts sample data on an empty DB. */
@Component
public class DataSeeder implements CommandLineRunner {
    private final AppUserRepository users;
    private final DeviceRepository devices;
    private final BackupService backups;
    private final AuthService auth;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public DataSeeder(AppUserRepository users, DeviceRepository devices, BackupService backups,
                      AuthService auth, AuditService audit, JdbcTemplate jdbc) {
        this.users = users;
        this.devices = devices;
        this.backups = backups;
        this.auth = auth;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        if (users.count() == 0) {
            boolean migrated = migrateLegacyAdmins();
            if (!migrated) addUser("admin", "admin123", "ADMIN");
        }
        // Demo accounts advertised on the login page. Created only when missing; existing users are never touched.
        ensureUser("operator", "operator123", "OPERATOR");
        ensureUser("viewer", "viewer123", "VIEWER");
        seedDevices();
        int rated = backups.backfillRisk();
        if (rated > 0) System.out.println("NetBackup: risk-rated " + rated + " existing version(s).");
    }

    /** Creates the user (BCrypt-hashed password) only if the username does not exist yet. */
    private void ensureUser(String name, String password, String role) {
        if (users.findByUsername(name).isPresent()) return;
        addUser(name, password, role);
        System.out.println("NetBackup: created demo user '" + name + "' (" + role + ").");
    }

    private void addUser(String name, String password, String role) {
        AppUser u = new AppUser();
        u.setUsername(name);
        u.setPasswordHash(auth.encode(password));
        u.setRole(role);
        users.save(u);
    }

    /** v1 -> v2 upgrade: old "admin" table accounts become ADMIN users; hash is upgraded to BCrypt at first login. */
    private boolean migrateLegacyAdmins() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = DATABASE() AND table_name = 'admin'", Integer.class);
        if (n == null || n == 0) return false;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT username AS u, password_hash AS p FROM admin");
        for (Map<String, Object> r : rows) {
            AppUser u = new AppUser();
            u.setUsername(String.valueOf(r.get("u")));
            u.setPasswordHash("{sha256}" + r.get("p"));
            u.setRole("ADMIN");
            users.save(u);
        }
        System.out.println("NetBackup: migrated " + rows.size() + " legacy admin account(s) to app_user.");
        return !rows.isEmpty();
    }

    private void seedDevices() {
        if (devices.count() > 0) return;
        String[][] data = {
            {"Core-Router-01", "10.0.1.1", "Router", "Cisco", "HQ Data Center", "ACTIVE", "4"},
            {"Core-Switch-01", "10.0.1.2", "Switch", "Cisco", "HQ Data Center", "ACTIVE", "3"},
            {"Edge-Firewall-01", "10.0.1.3", "Firewall", "Fortinet", "HQ Server Room", "ACTIVE", "2"},
            {"Branch-Router-02", "10.0.2.1", "Router", "Juniper", "Pune Branch", "ACTIVE", "3"},
            {"Access-Switch-05", "10.0.2.5", "Switch", "HP", "Mumbai Branch", "INACTIVE", "1"}
        };
        for (String[] r : data) {
            Device d = new Device();
            d.setName(r[0]); d.setIpAddress(r[1]); d.setType(r[2]);
            d.setVendor(r[3]); d.setLocation(r[4]); d.setStatus(r[5]);
            d = devices.save(d);
            int count = Integer.parseInt(r[6]);
            for (int i = 0; i < count; i++) backups.simulateBackup(d);   // inactive => FAILED
            audit.logAs("system", "SAMPLE_DATA", d.getId(), d.getName(), d.getCurrentVersion(),
                    d.getCurrentVersion() > 0 ? "SUCCESS" : "FAILED", count + " sample backup attempt(s)");
            if (r[0].equals("Core-Router-01")) {
                // Sample HIGH-risk change: interface shutdown + IP change + new default route.
                String bad = ConfigSimulator.generate(d.getName(), d.getIpAddress(), 4)
                        .replaceFirst(" no shutdown", " shutdown")
                        .replace("ip address 10.0.1.1", "ip address 10.0.1.11")
                        .replace("logging host 10.0.0.50\n", "logging host 10.0.0.50\nip route 0.0.0.0 0.0.0.0 10.0.1.254\n");
                backups.save(d, bad, "Core-Router-01-emergency.cfg", "UPLOAD", "Emergency change during outage (sample)");
                audit.logAs("operator", "UPLOAD", d.getId(), d.getName(), d.getCurrentVersion(), "SUCCESS", "Sample emergency change");
            }
        }
    }
}
