package com.netbackup.repo;

import com.netbackup.model.ConfigBackup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ConfigBackupRepository extends JpaRepository<ConfigBackup, Long> {
    List<ConfigBackup> findByDeviceIdOrderByIdDesc(Long deviceId);
    List<ConfigBackup> findTop8ByOrderByIdDesc();
    long countByStatus(String status);
    long countByRiskLevel(String riskLevel);
    List<ConfigBackup> findByCreatedAtAfter(LocalDateTime since);
    Optional<ConfigBackup> findFirstByDeviceIdAndStatusOrderByIdDesc(Long deviceId, String status);

    @Query("select coalesce(max(b.version), 0) from ConfigBackup b where b.deviceId = :id")
    int maxVersion(@Param("id") Long deviceId);

    @Transactional
    void deleteByDeviceId(Long deviceId);
}
