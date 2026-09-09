package com.hotel.audit;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;

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
    id = UUID.randomUUID();
    userId = user;
    this.action = action;
    entityType = "RESERVATION";
    entityId = entity;
    this.oldValue = oldValue;
    this.newValue = newValue;
    createdAt = Instant.now();
  }
}
