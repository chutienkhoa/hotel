package com.hotel.common;

import jakarta.persistence.*;
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

  @PrePersist
  /** Khởi tạo thời điểm tạo và cập nhật trước khi lưu thực thể lần đầu. */
  void created() {
    Instant now = Instant.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  /** Cập nhật thời điểm sửa đổi trước khi cập nhật thực thể. */
  void changed() {
    updatedAt = Instant.now();
  }

  /**
   * Gán người dùng chịu trách nhiệm tạo hoặc cập nhật thực thể.
   *
   * @param user định danh người dùng hiện tại
   */
  public void audit(UUID user) {
    if (createdBy == null) createdBy = user;
    updatedBy = user;
  }
}
