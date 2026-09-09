package com.hotel.reservation;

import com.hotel.common.AuditedEntity;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;

/** Đại diện cho lần lưu trú thực tế được tạo khi khách check-in. */
@Entity
@Table(name = "stay")
public class Stay extends AuditedEntity {
  @Id private UUID id;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "reservation_id", nullable = false, unique = true)
  private Reservation reservation;

  @Column(name = "actual_check_in_at", nullable = false)
  private Instant actualCheckInAt;

  /** Tạo thực thể rỗng cho JPA. */
  protected Stay() {}

  /**
   * Tạo stay mới cho reservation tại thời điểm check-in.
   *
   * @param r reservation đã check-in
   */
  public Stay(Reservation r) {
    id = UUID.randomUUID();
    reservation = r;
    actualCheckInAt = Instant.now();
  }
}
