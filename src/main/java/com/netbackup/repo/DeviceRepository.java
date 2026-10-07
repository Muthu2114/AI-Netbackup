package com.netbackup.repo;

import com.netbackup.model.Device;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, Long> {
    long countByStatus(String status);
}
