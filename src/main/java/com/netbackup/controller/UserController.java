package com.netbackup.controller;

import com.netbackup.model.AppUser;
import com.netbackup.repo.AppUserRepository;
import com.netbackup.service.AuditService;
import com.netbackup.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** User management - ADMIN only (enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/users")
public class UserController {
    private static final Set<String> ROLES = Set.of("ADMIN", "OPERATOR", "VIEWER");
    private final AppUserRepository users;
    private final AuthService auth;
    private final AuditService audit;

    public UserController(AppUserRepository users, AuthService auth, AuditService audit) {
        this.users = users;
        this.auth = auth;
        this.audit = audit;
    }

    @GetMapping
    public List<AppUser> list() { return users.findAll(); }

    @PostMapping
    public AppUser create(@RequestBody Map<String, String> b) {
        String name = b.get("username") == null ? "" : b.get("username").trim(), pw = b.get("password"), role = b.get("role");
        if (name.length() < 3) throw bad("Username must be at least 3 characters");
        if (pw == null || pw.length() < 8) throw bad("Password must be at least 8 characters");
        if (role == null || !ROLES.contains(role)) throw bad("Role must be ADMIN, OPERATOR or VIEWER");
        if (users.findByUsername(name).isPresent()) throw bad("Username already exists");
        AppUser u = new AppUser();
        u.setUsername(name);
        u.setPasswordHash(auth.encode(pw));
        u.setRole(role);
        u = users.save(u);
        audit.log("USER_CREATE", null, null, null, "SUCCESS", "Created " + name + " as " + role);
        return u;
    }

    @PutMapping("/{id}")
    public AppUser update(@PathVariable Long id, @RequestBody Map<String, Object> b) {
        AppUser u = users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        boolean self = u.getUsername().equals(AuditService.currentUser());
        StringBuilder what = new StringBuilder();
        if (b.get("role") != null) {
            String role = String.valueOf(b.get("role"));
            if (!ROLES.contains(role)) throw bad("Role must be ADMIN, OPERATOR or VIEWER");
            if (self && !role.equals("ADMIN")) throw bad("You cannot remove your own admin role");
            u.setRole(role);
            what.append("role=").append(role).append(' ');
        }
        if (b.get("enabled") != null) {
            boolean en = Boolean.parseBoolean(String.valueOf(b.get("enabled")));
            if (self && !en) throw bad("You cannot disable your own account");
            u.setEnabled(en);
            what.append("enabled=").append(en).append(' ');
        }
        if (b.get("password") != null && !String.valueOf(b.get("password")).isEmpty()) {
            String pw = String.valueOf(b.get("password"));
            if (pw.length() < 8) throw bad("Password must be at least 8 characters");
            u.setPasswordHash(auth.encode(pw));
            what.append("password reset");
        }
        u = users.save(u);
        audit.log("USER_UPDATE", null, null, null, "SUCCESS", u.getUsername() + ": " + what.toString().trim());
        return u;
    }

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
}
