package com.hotel.room;

import com.hotel.common.AuditedEntity;
import jakarta.persistence.*;
import java.util.*;

/** Đại diện cho một phòng vật lý và trạng thái vận hành của phòng. */
@Entity
@Table(name = "room")
public class Room extends AuditedEntity {
  @Id private UUID id;

  @Column(name = "room_number")
  private String roomNumber;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private RoomStatus status;

  @Column(nullable = false)
  private boolean active;

  /** Tạo thực thể rỗng cho JPA. */
  protected Room() {}

  /**
   * Trả về định danh phòng.
   *
   * @return định danh phòng
   */
  public UUID getId() {
    return id;
  }

  /**
   * Trả về trạng thái vận hành hiện tại của phòng.
   *
   * @return trạng thái phòng
   */
  public RoomStatus getStatus() {
    return status;
  }

  /**
   * Kiểm tra phòng có đang hoạt động hay không.
   *
   * @return {@code true} nếu phòng đang hoạt động
   */
  public boolean isActive() {
    return active;
  }

  /**
   * Chuyển phòng sẵn sàng sang trạng thái đang có khách.
   *
   * @throws IllegalStateException nếu phòng không hoạt động hoặc không sẵn sàng
   */
  public void occupy() {
    if (!active || status != RoomStatus.AVAILABLE)
      throw new IllegalStateException("Room is not available");
    status = RoomStatus.OCCUPIED;
  }
}
