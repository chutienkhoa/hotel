package com.hotel.security;

import com.hotel.common.AuditedEntity;
import jakarta.persistence.*;
import java.util.*;

/** Đại diện cho một permission có thể được gán thông qua role. */
@Entity
@Table(name = "permission")
public class Permission extends AuditedEntity {
  @Id private UUID id;

  @Column(nullable = false, unique = true)
  private String code;

  private String name;

  /** Tạo thực thể rỗng cho JPA. */
  protected Permission() {}

  /**
   * Trả về mã permission.
   *
   * @return mã permission
   */
  public String getCode() {
    return code;
  }
}
