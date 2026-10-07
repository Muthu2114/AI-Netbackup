package com.netbackup.service;

import com.netbackup.model.AppUser;
import com.netbackup.repo.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class AuthService {
    private final AppUserRepository users;
    private final PasswordEncoder encoder;

    public AuthService(AppUserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    public static String sha256(String text) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Returns the user when username/password are correct and the account is enabled. */
    public Optional<AppUser> authenticate(String username, String password) {
        if (username == null || password == null) return Optional.empty();
        return users.findByUsername(username).filter(AppUser::isEnabled).filter(u -> matches(u, password));
    }

    private boolean matches(AppUser u, String raw) {
        String h = u.getPasswordHash();
        if (h.startsWith("{sha256}")) {            // legacy v1 account: verify, then upgrade to BCrypt
            if (!h.substring(8).equals(sha256(raw))) return false;
            u.setPasswordHash(encoder.encode(raw));
            users.save(u);
            return true;
        }
        return encoder.matches(raw, h);
    }

    public String encode(String raw) { return encoder.encode(raw); }
}
