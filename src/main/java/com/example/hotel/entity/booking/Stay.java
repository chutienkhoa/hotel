package com.example.hotel.entity.booking;

import com.example.hotel.entity.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
     * @param reservation reservation đã check-in
     */
    public Stay(Reservation reservation) {
        id = UUID.randomUUID();
        this.reservation = reservation;
        actualCheckInAt = Instant.now();
    }
}
