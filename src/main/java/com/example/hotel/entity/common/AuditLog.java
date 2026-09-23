package com.example.hotel.entity.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Lưu vết một thao tác nghiệp vụ đã được thực hiện bởi người dùng. */
@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "old_value")
    private String oldValue;

    @Column(name = "new_value")
    private String newValue;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Tạo thực thể rỗng cho JPA. */
    protected AuditLog() {}

    /**
     * Tạo bản ghi audit cho một reservation.
     *
     * @param user định danh người thực hiện
     * @param action thao tác được ghi nhận
     * @param entity định danh reservation liên quan
     * @param oldValue trạng thái hoặc giá trị trước thao tác
     * @param newValue trạng thái hoặc giá trị sau thao tác
     */
    public AuditLog(UUID user, String action, UUID entity, String oldValue, String newValue) {
        this(user, action, "RESERVATION", entity, oldValue, newValue);
    }

    /**
     * Tạo bản ghi audit cho một loại thực thể xác định.
     *
     * @param user định danh người thực hiện
     * @param action thao tác được ghi nhận
     * @param entityType loại thực thể bị tác động, ví dụ {@code APP_USER}
     * @param entity định danh thực thể liên quan
     * @param oldValue trạng thái hoặc giá trị trước thao tác
     * @param newValue trạng thái hoặc giá trị sau thao tác
     */
    public AuditLog(
            UUID user, String action, String entityType, UUID entity, String oldValue, String newValue) {
        id = UUID.randomUUID();
        userId = user;
        this.action = action;
        this.entityType = entityType;
        entityId = entity;
        this.oldValue = oldValue;
        this.newValue = newValue;
        createdAt = Instant.now();
    }

    /**
     * Returns the identifier of the user who performed the audited action.
     *
     * @return the actor's user identifier
     */
    public UUID getUserId() {
        return userId;
    }

    /**
     * Returns the stable action identifier recorded for this audit row.
     *
     * @return the audited action, e.g. {@code CHECK_IN}
     */
    public String getAction() {
        return action;
    }

    /**
     * Returns the identifier of the entity this audit row was recorded against.
     *
     * @return the audited entity identifier
     */
    public UUID getEntityId() {
        return entityId;
    }

    /**
     * Returns the instant this audit row was recorded.
     *
     * @return the audit timestamp
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
