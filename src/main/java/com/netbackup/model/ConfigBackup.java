package com.netbackup.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Entity
public class ConfigBackup {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long deviceId;
    private int version;              // 0 for failed backups
    private String fileName;
    @Lob @Column(columnDefinition = "LONGTEXT")
    private String content;
    private String checksum;
    private String status;            // SUCCESS | FAILED
    private String source;            // SIMULATED | UPLOAD | RESTORE
    private String note;
    private String createdBy;
    private String riskLevel;         // LOW | MEDIUM | HIGH (null for failed backups)
    private Integer riskScore;        // 0-100
    @Column(columnDefinition = "TEXT")
    private String riskDetails;       // lines of: SEVERITY|rule|config line
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }
}
