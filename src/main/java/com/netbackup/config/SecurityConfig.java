package com.netbackup.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netbackup.repo.AppUserRepository;
import com.netbackup.service.AuditService;
import com.netbackup.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    /** Also stops Spring Boot from printing a random default password at startup. */
    @Bean
    public UserDetailsService userDetailsService(AppUserRepository users) {
        return name -> users.findByUsername(name)
                .map(u -> User.withUsername(u.getUsername()).password(u.getPasswordHash())
                        .roles(u.getRole()).disabled(!u.isEnabled()).build())
                .orElseThrow(() -> new UsernameNotFoundException(name));
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwt, AppUserRepository users,
                                           AuditService audit, ObjectMapper json) throws Exception {
        http.csrf(c -> c.disable())   // stateless JWT in the Authorization header, no cookies
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/users/**", "/api/audit/**", "/api/scheduler/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/devices/**").hasRole("ADMIN")
                .requestMatchers("/api/auth/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/**").authenticated()            // ADMIN, OPERATOR, VIEWER
                .requestMatchers("/api/**").hasAnyRole("ADMIN", "OPERATOR")            // all writes
                .anyRequest().permitAll())                                             // static frontend files
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> writeJson(json, res, 401, "Unauthorized - please log in"))
                .accessDeniedHandler((req, res, ex) -> {
                    audit.log("ACCESS_DENIED", null, null, null, "DENIED", req.getMethod() + " " + req.getRequestURI());
                    writeJson(json, res, 403, "Access denied for your role");
                }))
            .addFilterBefore(new JwtFilter(jwt, users), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeJson(ObjectMapper json, HttpServletResponse res, int status, String msg) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write(json.writeValueAsString(Map.of("message", msg)));
    }

    /** Reads "Authorization: Bearer <jwt>", checks the user still exists and is enabled, uses the CURRENT role from the DB. */
    static class JwtFilter extends OncePerRequestFilter {
        private final JwtService jwt;
        private final AppUserRepository users;

        JwtFilter(JwtService jwt, AppUserRepository users) { this.jwt = jwt; this.users = users; }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            String h = req.getHeader("Authorization");
            if (h != null && h.startsWith("Bearer ")) {
                String username = jwt.validate(h.substring(7));
                if (username != null) {
                    users.findByUsername(username).filter(u -> u.isEnabled()).ifPresent(u -> {
                        var auth = new UsernamePasswordAuthenticationToken(u.getUsername(), null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole())));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    });
                }
            }
            chain.doFilter(req, res);
        }
    }
}
