package com.example.hotel.entity.booking;

import com.example.hotel.entity.common.AuditedEntity;
import com.example.hotel.entity.room.Room;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Records one continuous interval of ACTUAL PHYSICAL ROOM OCCUPANCY for a Stay, distinct from the
 * immutable BOOKING/PRICING snapshot held by {@link ReservationRoom}. An open row ({@code
 * assignedTo == null}) is the room the guest currently occupies; closing it and appending a new
 * open row is how a Room Change is represented, preserving full history.
 *
 * <p>{@code originalReservationRoom} is the lineage anchor: every row produced from the same
 * originally booked room (whether the initial Check-in assignment or any later replacement)
 * references the same {@link ReservationRoom}, which is never mutated by Room Change. This lets
 * the remaining planned occupancy boundary for a lineage always be read from that one immutable
 * source, with no duplicated or copied date field.
 */
@Entity
@Table(name = "stay_room_assignment")
public class StayRoomAssignment extends AuditedEntity {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stay_id", nullable = false)
    private Stay stay;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_reservation_room_id", nullable = false)
    private ReservationRoom originalReservationRoom;

    @Column(name = "assigned_from", nullable = false)
    private Instant assignedFrom;

    @Column(name = "assigned_to")
    private Instant assignedTo;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private RoomChangeReason reason;

    private String notes;

    /** Tạo thực thể rỗng cho JPA. */
    protected StayRoomAssignment() {}

    /**
     * Tạo assignment mở (đang là phòng hiện tại) cho một lần check-in hoặc đổi phòng.
     *
     * @param stay lưu trú sở hữu assignment
     * @param room phòng vật lý hiện đang được gán
     * @param originalReservationRoom ReservationRoom gốc, dùng làm mốc lineage bất biến
     * @param assignedFrom thời điểm bắt đầu chiếm phòng này, theo Clock chính thức
     * @param reason lý do đổi phòng; {@code null} cho assignment khởi tạo lúc check-in
     * @param notes ghi chú tùy chọn; bắt buộc khi {@code reason == OTHER}
     */
    public StayRoomAssignment(
            Stay stay,
            Room room,
            ReservationRoom originalReservationRoom,
            Instant assignedFrom,
            RoomChangeReason reason,
            String notes) {
        id = UUID.randomUUID();
        this.stay = stay;
        this.room = room;
        this.originalReservationRoom = originalReservationRoom;
        this.assignedFrom = assignedFrom;
        this.assignedTo = null;
        this.reason = reason;
        this.notes = notes;
    }

    /**
     * Trả về định danh assignment.
     *
     * @return định danh assignment
     */
    public UUID getId() {
        return id;
    }

    /**
     * Trả về lưu trú sở hữu assignment.
     *
     * @return lưu trú sở hữu
     */
    public Stay getStay() {
        return stay;
    }

    /**
     * Trả về phòng vật lý hiện đang được gán bởi assignment này.
     *
     * @return phòng vật lý
     */
    public Room getRoom() {
        return room;
    }

    /**
     * Trả về ReservationRoom gốc dùng làm mốc lineage bất biến.
     *
     * @return ReservationRoom gốc
     */
    public ReservationRoom getOriginalReservationRoom() {
        return originalReservationRoom;
    }

    /**
     * Trả về thời điểm bắt đầu chiếm phòng này.
     *
     * @return thời điểm bắt đầu
     */
    public Instant getAssignedFrom() {
        return assignedFrom;
    }

    /**
     * Trả về thời điểm kết thúc chiếm phòng này, hoặc {@code null} nếu đang là phòng hiện tại.
     *
     * @return thời điểm kết thúc, hoặc {@code null}
     */
    public Instant getAssignedTo() {
        return assignedTo;
    }

    /**
     * Trả về lý do đổi phòng, hoặc {@code null} cho assignment khởi tạo lúc check-in.
     *
     * @return lý do đổi phòng, hoặc {@code null}
     */
    public RoomChangeReason getReason() {
        return reason;
    }

    /**
     * Trả về ghi chú tùy chọn.
     *
     * @return ghi chú, hoặc {@code null}
     */
    public String getNotes() {
        return notes;
    }

    /**
     * Kiểm tra assignment có đang mở (là phòng hiện tại) hay không.
     *
     * @return {@code true} nếu đang mở
     */
    public boolean isOpen() {
        return assignedTo == null;
    }

    /**
     * Đóng assignment đang mở tại thời điểm được chỉ định, biến nó thành lịch sử bất biến.
     *
     * @param instant thời điểm đóng, theo Clock chính thức
     * @throws IllegalStateException nếu assignment đã đóng
     */
    public void close(Instant instant) {
        if (assignedTo != null) {
            throw new IllegalStateException("Assignment is already closed");
        }
        assignedTo = instant;
    }
}
