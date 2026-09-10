package com.example.hotel.entity.booking;

import com.example.hotel.entity.common.AuditedEntity;
import com.example.hotel.entity.room.Room;
import jakarta.persistence.*;
import java.math.*;
import java.time.*;
import java.util.*;

/** Lưu snapshot giá và thời gian cho một phòng thuộc reservation. */
@Entity
@Table(name = "reservation_room")
public class ReservationRoom extends AuditedEntity {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "reservation_id", nullable = false)
  private Reservation reservation;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "room_id", nullable = false)
  private Room room;

  @Column(nullable = false)
  private LocalDate checkInDate;

  @Column(nullable = false)
  private LocalDate checkOutDate;

  @Column(nullable = false, precision = 19, scale = 6)
  private BigDecimal nightlyRate;

  @Column(nullable = false, precision = 19, scale = 6)
  private BigDecimal totalAmount;

  /** Tạo thực thể rỗng cho JPA. */
  protected ReservationRoom() {}

  /**
   * Tạo dòng phòng và tính tổng tiền dựa trên số đêm.
   *
   * @param r reservation sở hữu dòng phòng
   * @param room phòng được gán
   * @param in ngày nhận phòng
   * @param out ngày trả phòng
   * @param rate giá mỗi đêm được snapshot
   */
  public ReservationRoom(Reservation r, Room room, LocalDate in, LocalDate out, BigDecimal rate) {
    id = UUID.randomUUID();
    reservation = r;
    this.room = room;
    checkInDate = in;
    checkOutDate = out;
    nightlyRate = rate;
    totalAmount =
        rate.multiply(BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(in, out)));
  }

  /**
   * Trả về phòng được gán.
   *
   * @return phòng được gán
   */
  public Room getRoom() {
    return room;
  }

  /**
   * Trả về tổng tiền snapshot của dòng phòng.
   *
   * @return tổng tiền dòng phòng
   */
  public BigDecimal getTotalAmount() {
    return totalAmount;
  }
}
