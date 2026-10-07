package com.netbackup.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Entity
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    private String action;       // LOGIN, BACKUP, UPLOAD, RESTORE, DEVICE_CREATE, ...
    private Long deviceId;
    private String deviceName;
    private Integer version;
    private String status;       // SUCCESS | FAILED | DENIED
    @Column(length = 500)
    private String details;
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }
}
