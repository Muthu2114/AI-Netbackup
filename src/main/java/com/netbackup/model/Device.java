package com.netbackup.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Entity
public class Device {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String ipAddress;
    private String type;      // Router, Switch, Firewall
    private String vendor;
    private String location;
    private String status = "ACTIVE";   // ACTIVE | INACTIVE
    private int currentVersion = 0;
    private LocalDateTime lastBackupAt;
    private Boolean autoBackup = true;   // included in scheduled backups (null is treated as true)
}
