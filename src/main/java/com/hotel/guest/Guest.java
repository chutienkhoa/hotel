package com.hotel.guest;

import com.hotel.common.AuditedEntity;
import jakarta.persistence.*;
import java.util.*;

/** Đại diện cho khách lưu trú được tham chiếu bởi reservation. */
@Entity
@Table(name = "guest")
public class Guest extends AuditedEntity {
  @Id private UUID id;

  @Column(name = "guest_code")
  private String guestCode;

  /** Tạo thực thể rỗng cho JPA. */
  protected Guest() {}

  /**
   * Trả về định danh của khách.
   *
   * @return định danh khách
   */
  public UUID getId() {
    return id;
  }
}
