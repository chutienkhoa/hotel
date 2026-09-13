package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Cung cấp thao tác lưu trữ cho các lần lưu trú. */
public interface StayRepository extends JpaRepository<Stay, UUID> {
    /**
     * Counts Stays in the supplied lifecycle status.
     *
     * @param status Stay status to count
     * @return matching Stay count
     */
    long countByStatus(StayStatus status);

    /**
     * Kiểm tra reservation đã có stay hay chưa.
     *
     * @param reservationId định danh reservation
     * @return {@code true} nếu stay đã tồn tại
     */
    boolean existsByReservationId(UUID reservationId);

    /**
     * Finds the unique Stay created for a Reservation.
     *
     * @param reservationId owning Reservation identifier
     * @return the matching Stay when the Reservation has been checked in
     */
    Optional<Stay> findByReservationId(UUID reservationId);

    /**
     * Locks the unique Stay created for a Reservation before a balance-affecting operation.
     *
     * @param reservationId owning Reservation identifier
     * @return locked unique Stay, when it exists
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Stay s WHERE s.reservation.id = :reservationId")
    Optional<Stay> findByReservationIdForUpdate(@Param("reservationId") UUID reservationId);

    /**
     * Locks a Stay to serialize Payment state transitions that affect the same balance context.
     *
     * @param id Stay identifier
     * @return locked Stay when it exists
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Stay s WHERE s.id = :id")
    Optional<Stay> findByIdForUpdate(@Param("id") UUID id);
}
