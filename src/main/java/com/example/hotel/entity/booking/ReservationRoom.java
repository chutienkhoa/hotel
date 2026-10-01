package com.example.hotel.entity.booking;

import com.example.hotel.entity.common.AuditedEntity;
import com.example.hotel.entity.room.Room;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

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
     * @param reservation reservation sở hữu dòng phòng
     * @param room phòng được gán
     * @param checkInDate ngày nhận phòng
     * @param checkOutDate ngày trả phòng
     * @param nightlyRate giá mỗi đêm được snapshot
     */
    public ReservationRoom(
            Reservation reservation,
            Room room,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            BigDecimal nightlyRate) {
        id = UUID.randomUUID();
        this.reservation = reservation;
        this.room = room;
        this.checkInDate = checkInDate;
        this.checkOutDate = checkOutDate;
        this.nightlyRate = nightlyRate;
        totalAmount =
                nightlyRate.multiply(
                        BigDecimal.valueOf(ChronoUnit.DAYS.between(checkInDate, checkOutDate)));
    }

    /**
     * Returns the identifier of this booked-room snapshot.
     *
     * @return the identifier
     */
    public UUID getId() {
        return id;
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
     * Returns the check-in date snapshot for the assigned room.
     *
     * @return the room check-in date
     */
    public LocalDate getCheckInDate() {
        return checkInDate;
    }

    /**
     * Returns the check-out date snapshot for the assigned room.
     *
     * @return the room check-out date
     */
    public LocalDate getCheckOutDate() {
        return checkOutDate;
    }

    /**
     * Returns the nightly-rate snapshot for the assigned room.
     *
     * @return the nightly rate
     */
    public BigDecimal getNightlyRate() {
        return nightlyRate;
    }

    /**
     * Trả về tổng tiền snapshot của dòng phòng.
     *
     * @return tổng tiền dòng phòng
     */
    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    /**
     * Replaces only the assigned Room. Dates, nightly rate and total are the immutable booking snapshot and are
     * deliberately left untouched (no repricing).
     *
     * @param replacement the newly assigned Room
     */
    void replaceRoom(Room replacement) {
        room = replacement;
    }

    /**
     * Refreshes the date and price snapshot while its owning Reservation remains a draft.
     *
     * @param checkInDate replacement check-in snapshot
     * @param checkOutDate replacement check-out snapshot
     * @param nightlyRate replacement nightly-rate snapshot
     */
    void updateDraftSnapshot(LocalDate checkInDate, LocalDate checkOutDate, BigDecimal nightlyRate) {
        if (reservation.getStatus() != ReservationStatus.DRAFT) {
            throw new IllegalStateException("Only draft reservation rooms can be edited");
        }
        this.checkInDate = checkInDate;
        this.checkOutDate = checkOutDate;
        this.nightlyRate = nightlyRate;
        totalAmount = nightlyRate.multiply(
                BigDecimal.valueOf(ChronoUnit.DAYS.between(checkInDate, checkOutDate)));
    }

    /**
     * Moves this confirmed pre-check-in snapshot to a new interval while preserving its Room and nightly rate.
     *
     * @param checkInDate replacement check-in date
     * @param checkOutDate replacement check-out date
     * @throws IllegalStateException if the owning Reservation is not CONFIRMED
     */
    void changeConfirmedDates(LocalDate checkInDate, LocalDate checkOutDate) {
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new IllegalStateException("Only confirmed reservation rooms can change dates");
        }
        this.checkInDate = checkInDate;
        this.checkOutDate = checkOutDate;
        totalAmount = nightlyRate.multiply(
                BigDecimal.valueOf(ChronoUnit.DAYS.between(checkInDate, checkOutDate)));
    }
}
