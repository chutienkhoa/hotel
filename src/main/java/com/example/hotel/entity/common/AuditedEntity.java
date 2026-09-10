package com.example.hotel.entity.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;
import java.util.UUID;

/** Cung cấp các trường audit dùng chung cho thực thể được lưu trữ. */
@MappedSuperclass
public abstract class AuditedEntity {
    @Column(nullable = false, updatable = false)
    protected Instant createdAt;

    @Column(nullable = false, updatable = false)
    protected UUID createdBy;

    @Column(nullable = false)
    protected Instant updatedAt;

    @Column(nullable = false)
    protected UUID updatedBy;

    /** Khởi tạo thời điểm tạo và cập nhật trước khi lưu thực thể lần đầu. */
    @PrePersist
    void created() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    /** Cập nhật thời điểm sửa đổi trước khi cập nhật thực thể. */
    @PreUpdate
    void changed() {
        updatedAt = Instant.now();
    }

    /**
     * Gán người dùng chịu trách nhiệm tạo hoặc cập nhật thực thể.
     *
     * @param user định danh người dùng hiện tại
     */
    public void audit(UUID user) {
        if (createdBy == null) {
            createdBy = user;
        }
        updatedBy = user;
    }

    /**
     * Returns the user recorded as the entity creator.
     *
     * @return the creator identifier
     */
    public UUID getCreatedBy() {
        return createdBy;
    }

    /**
     * Returns the user recorded as the most recent updater.
     *
     * @return the latest updater identifier
     */
    public UUID getUpdatedBy() {
        return updatedBy;
    }
}
