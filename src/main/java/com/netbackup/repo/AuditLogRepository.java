package com.netbackup.repo;

import com.netbackup.model.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    @Query("select a from AuditLog a where (:user is null or a.username = :user) " +
           "and (:action is null or a.action = :action) and (:status is null or a.status = :status) order by a.id desc")
    List<AuditLog> search(@Param("user") String user, @Param("action") String action,
                          @Param("status") String status, Pageable page);

    List<AuditLog> findTop8ByOrderByIdDesc();
}
