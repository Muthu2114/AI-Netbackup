package com.netbackup.service;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {
    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(@Value("${netbackup.jwt.secret}") String secret,
                      @Value("${netbackup.jwt.expiration-minutes:480}") long expirationMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));   // needs 32+ characters
        this.expirationMinutes = expirationMinutes;
    }

    public long getExpirationMinutes() { return expirationMinutes; }

    public String generate(String username, String role) {
        Date now = new Date();
        return Jwts.builder().subject(username).claim("role", role)
                .issuedAt(now).expiration(new Date(now.getTime() + expirationMinutes * 60_000))
                .signWith(key).compact();
    }

    /** Returns the username inside a valid, unexpired token, otherwise null. */
    public String validate(String token) {
        try {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
