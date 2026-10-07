package com.netbackup.controller;

import com.netbackup.model.AppUser;
import com.netbackup.repo.AppUserRepository;
import com.netbackup.service.AuditService;
import com.netbackup.service.AuthService;
import com.netbackup.service.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final JwtService jwt;
    private final AuditService audit;
    private final AppUserRepository users;

    public AuthController(AuthService auth, JwtService jwt, AuditService audit, AppUserRepository users) {
        this.auth = auth;
        this.jwt = jwt;
        this.audit = audit;
        this.users = users;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String name = body.get("username");
        AppUser u = auth.authenticate(name, body.get("password")).orElse(null);
        if (u == null) {
            audit.logAs(name == null ? "unknown" : name, "LOGIN", null, null, null, "FAILED", "Invalid username or password");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        audit.logAs(u.getUsername(), "LOGIN", null, null, null, "SUCCESS", "Role " + u.getRole());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", jwt.generate(u.getUsername(), u.getRole()));
        out.put("username", u.getUsername());
        out.put("role", u.getRole());
        out.put("expiresInMinutes", jwt.getExpirationMinutes());
        return out;
    }

    @GetMapping("/me")
    public Map<String, String> me(Authentication a) {
        AppUser u = users.findByUsername(a.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
        return Map.of("username", u.getUsername(), "role", u.getRole());
    }

    @PostMapping("/change-password")
    public Map<String, String> changePassword(Authentication a, @RequestBody Map<String, String> body) {
        String cur = body.get("currentPassword"), next = body.get("newPassword");
        if (next == null || next.length() < 8)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be at least 8 characters");
        AppUser u = auth.authenticate(a.getName(), cur).orElseThrow(() -> {
            audit.log("PASSWORD_CHANGE", null, null, null, "FAILED", "Current password incorrect");
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        });
        u.setPasswordHash(auth.encode(next));
        users.save(u);
        audit.log("PASSWORD_CHANGE", null, null, null, "SUCCESS", "Password changed");
        return Map.of("message", "Password changed");
    }
}
