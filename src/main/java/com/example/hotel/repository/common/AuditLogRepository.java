package com.example.hotel.repository.common;

import com.example.hotel.entity.common.AuditLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Cung cấp thao tác lưu trữ cho các bản ghi audit. */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * Lists every audit row for one entity in stable chronological order, the read path used by the
     * Reservation Operational Timeline.
     *
     * @param entityType audited entity type, e.g. {@code RESERVATION}
     * @param entityId audited entity identifier
     * @return matching rows ordered oldest first, ties broken by identifier
     */
    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc(String entityType, UUID entityId);
}
