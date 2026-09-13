package com.example.hotel.entity.booking;

import com.example.hotel.entity.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Đại diện cho lần lưu trú thực tế được tạo khi khách check-in. */
@Entity
@Table(name = "stay")
public class Stay extends AuditedEntity {
    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false, unique = true)
    private Reservation reservation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StayStatus status;

    @Column(name = "actual_check_in_at", nullable = false)
    private Instant actualCheckInAt;

    @Column(name = "actual_check_out_at")
    private Instant actualCheckOutAt;

    /** Tạo thực thể rỗng cho JPA. */
    protected Stay() {}

    /**
     * Tạo stay mới cho reservation tại thời điểm check-in.
     *
     * @param reservation reservation đã check-in
     */
    public Stay(Reservation reservation) {
        id = UUID.randomUUID();
        this.reservation = reservation;
        status = StayStatus.CHECKED_IN;
        actualCheckInAt = Instant.now();
        actualCheckOutAt = null;
    }

    /**
     * Returns the backend-generated technical identifier of this stay.
     *
     * @return the stay identifier
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the reservation associated with this stay.
     *
     * @return the checked-in reservation
     */
    public Reservation getReservation() {
        return reservation;
    }

    /**
     * Returns the current lifecycle status of this stay.
     *
     * @return the current stay status
     */
    public StayStatus getStatus() {
        return status;
    }

    /**
     * Returns the time recorded by the backend when check-in completed.
     *
     * @return the actual check-in time
     */
    public Instant getActualCheckInAt() {
        return actualCheckInAt;
    }

    /**
     * Returns the time recorded by the backend when check-out completed.
     *
     * @return the actual check-out time, or {@code null} before check-out
     */
    public Instant getActualCheckOutAt() {
        return actualCheckOutAt;
    }

    /**
     * Transitions this checked-in stay to checked out and records the backend checkout time.
     *
     * @throws IllegalStateException if the stay is not currently checked in
     */
    public void checkOut() {
        if (status != StayStatus.CHECKED_IN) {
            throw new IllegalStateException("Invalid stay state transition");
        }
        status = StayStatus.CHECKED_OUT;
        actualCheckOutAt = Instant.now();
    }
}
