package com.example.hotel.entity.room;

import com.example.hotel.entity.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Đại diện cho một phòng vật lý và trạng thái vận hành của phòng. */
@Entity
@Table(name = "room")
public class Room extends AuditedEntity {

    /**
     * Approved sources for entering MAINTENANCE or OUT_OF_ORDER: an available room, or a dirty room
     * vacated by check-out or Room Change. An occupied room must go through Room Change first, since
     * only Room Change may vacate a guest.
     */
    private static final Set<RoomStatus> MAINTENANCE_SOURCE_STATUSES =
            EnumSet.of(RoomStatus.AVAILABLE, RoomStatus.DIRTY);

    @Id
    private UUID id;

    @Column(name = "room_number", nullable = false, unique = true)
    private String roomNumber;

    @ManyToOne(optional = false)
    @JoinColumn(name = "room_type_id", nullable = false)
    private RoomType roomType;

    @Column(length = 32)
    private String floor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoomStatus status;

    @Column(nullable = false)
    private boolean active;

    /** Tạo thực thể rỗng cho JPA. */
    protected Room() {}

    /**
     * Creates an active room with the required room type and initial AVAILABLE operational status.
     *
     * @param id internal technical room identifier
     * @param roomNumber business room number
     * @param roomType required room type
     * @param floor room floor
     * @return the new room entity
     */
    public static Room create(UUID id, String roomNumber, RoomType roomType, String floor) {
        Room room = new Room();
        room.id = id;
        room.status = RoomStatus.AVAILABLE;
        room.active = true;
        room.updateProfile(roomNumber, roomType, floor);
        return room;
    }

    /**
     * Updates only the mutable room-profile fields approved for Room Management.
     *
     * @param roomNumber business room number
     * @param roomType required room type
     * @param floor room floor
     */
    public void updateProfile(String roomNumber, RoomType roomType, String floor) {
        this.roomNumber = roomNumber;
        this.roomType = roomType;
        this.floor = floor;
    }

    /**
     * Trả về định danh phòng.
     *
     * @return định danh phòng
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the room number displayed to reservation users.
     *
     * @return the room number
     */
    public String getRoomNumber() {
        return roomNumber;
    }

    /**
     * Returns the required room type assigned to this room.
     *
     * @return the room type
     */
    public RoomType getRoomType() {
        return roomType;
    }

    /**
     * Returns the floor on which this room is located.
     *
     * @return the floor, or {@code null} when it has not been supplied
     */
    public String getFloor() {
        return floor;
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
    /**
     * Tells whether the Room belongs to bookable inventory: it is active and not currently
     * MAINTENANCE or OUT_OF_ORDER. OCCUPIED, DIRTY and CLEANING describe the Room now, not during a
     * requested future stay, so they do not remove it from bookable inventory. Booking availability
     * for a period additionally requires no overlapping Reservation.
     *
     * @return {@code true} when the Room may be offered for a booking period
     */
    public boolean isBookableInventory() {
        return active && status != RoomStatus.MAINTENANCE && status != RoomStatus.OUT_OF_ORDER;
    }

    /**
     * Tells whether the Room can be occupied right now. In V1 the only check-in-ready state is an
     * active AVAILABLE Room; this mirrors the validation enforced by check-in.
     *
     * @return {@code true} when an immediate check-in can use the Room
     */
    public boolean isReadyForCheckIn() {
        return active && status == RoomStatus.AVAILABLE;
    }

    public void occupy() {
        if (!active || status != RoomStatus.AVAILABLE) {
            throw new IllegalStateException("Room is not available");
        }
        status = RoomStatus.OCCUPIED;
    }

    /**
     * Transitions an occupied room to dirty after a completed check-out.
     *
     * @throws IllegalStateException if the room is not currently occupied
     */
    public void markDirty() {
        transition(RoomStatus.OCCUPIED, RoomStatus.DIRTY, "check out");
    }

    /**
     * Vacates an occupied room as the old side of a Room Change. A room a guest has just left is not
     * clean, so it becomes DIRTY exactly like a checked-out room and only returns to AVAILABLE through
     * the housekeeping transitions (DIRTY, then CLEANING, then AVAILABLE).
     *
     * @throws IllegalStateException if the room is not currently occupied
     */
    public void releaseForRoomChange() {
        transition(RoomStatus.OCCUPIED, RoomStatus.DIRTY, "room change release");
    }

    /** Transitions a dirty room into cleaning. */
    public void startCleaning() {
        transition(RoomStatus.DIRTY, RoomStatus.CLEANING, "start cleaning");
    }

    /** Transitions a cleaning room back to available. */
    public void finishCleaning() {
        transition(RoomStatus.CLEANING, RoomStatus.AVAILABLE, "finish cleaning");
    }

    /**
     * Transitions an available or dirty room into maintenance. A dirty room (vacated by check-out or
     * Room Change) can go directly into maintenance without a fictitious housekeeping cycle first.
     *
     * @throws IllegalStateException if the room is occupied, cleaning, in maintenance, or out of order
     */
    public void startMaintenance() {
        transition(MAINTENANCE_SOURCE_STATUSES, RoomStatus.MAINTENANCE, "start maintenance");
    }

    /** Transitions a room in maintenance back to available. */
    public void finishMaintenance() {
        transition(RoomStatus.MAINTENANCE, RoomStatus.AVAILABLE, "finish maintenance");
    }

    /**
     * Transitions an available or dirty room out of service. A dirty room (vacated by check-out or
     * Room Change) can go directly out of service without a fictitious housekeeping cycle first.
     *
     * @throws IllegalStateException if the room is occupied, cleaning, in maintenance, or out of order
     */
    public void markOutOfOrder() {
        transition(MAINTENANCE_SOURCE_STATUSES, RoomStatus.OUT_OF_ORDER, "mark out of order");
    }

    /** Restores an out-of-order room to available service. */
    public void restoreToService() {
        transition(RoomStatus.OUT_OF_ORDER, RoomStatus.AVAILABLE, "restore to service");
    }

    /**
     * Applies one approved Room status transition without changing Room profile, type, active, or audit data.
     *
     * @param expectedStatus the only permitted current status
     * @param targetStatus the approved status after the operation
     * @param operationName human-readable operation name used in the rejection message
     * @throws IllegalStateException if the Room is not in the permitted current status
     */
    private void transition(RoomStatus expectedStatus, RoomStatus targetStatus, String operationName) {
        transition(EnumSet.of(expectedStatus), targetStatus, operationName);
    }

    /**
     * Applies one approved Room status transition from any of several permitted current statuses,
     * without changing Room profile, type, active, or audit data.
     *
     * @param expectedStatuses the permitted current statuses
     * @param targetStatus the approved status after the operation
     * @param operationName human-readable operation name used in the rejection message
     * @throws IllegalStateException if the Room is not in one of the permitted current statuses
     */
    private void transition(Set<RoomStatus> expectedStatuses, RoomStatus targetStatus, String operationName) {
        if (!expectedStatuses.contains(status)) {
            throw new IllegalStateException(
                    "Room cannot " + operationName + " from status " + status);
        }
        status = targetStatus;
    }
}
