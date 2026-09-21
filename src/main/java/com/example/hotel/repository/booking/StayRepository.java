package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.Stay;
import com.example.hotel.entity.booking.StayStatus;
import java.time.Instant;
import java.time.LocalDate;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.Collection;
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

    /** Counts checked-in Stays scheduled by their Reservation to check out on one business date. */
    @Query("SELECT COUNT(s) FROM Stay s WHERE s.status = :status AND s.reservation.checkOutDate = :currentDate")
    long countCheckedInScheduledForCheckOutOn(
            @Param("status") StayStatus status, @Param("currentDate") LocalDate currentDate);

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

    /**
     * Finds the booked rooms of Stays overlapping {@code [start, end)} that have no StayRoomAssignment at
     * all for that lineage, which means the actual occupancy history is incomplete.
     *
     * @param start inclusive start Instant of the report period
     * @param end exclusive end Instant of the report period
     * @return ReservationRoom identifiers lacking assignment coverage, in identifier order
     */
    @Query(
            "SELECT rr.id FROM Stay s, ReservationRoom rr "
                    + "WHERE rr.reservation = s.reservation "
                    + "AND s.actualCheckInAt < :end "
                    + "AND (s.actualCheckOutAt IS NULL OR s.actualCheckOutAt > :start) "
                    + "AND NOT EXISTS (SELECT 1 FROM StayRoomAssignment a "
                    + "WHERE a.stay = s AND a.originalReservationRoom = rr) "
                    + "ORDER BY rr.id")
    List<UUID> findReservationRoomIdsWithoutAssignmentOverlapping(
            @Param("start") Instant start, @Param("end") Instant end);

    /**
     * Loads the Stays of a given status whose Reservation has a given status, with the Reservation and its Guest,
     * in one query, ordered by planned check-out date then Reservation number.
     *
     * @param stayStatus Stay status
     * @param reservationStatus Reservation status
     * @return matching Stays with Reservation and Guest initialized
     */
    @Query("SELECT s FROM Stay s JOIN FETCH s.reservation r JOIN FETCH r.guest "
            + "WHERE s.status = :stayStatus AND r.status = :reservationStatus "
            + "ORDER BY r.checkOutDate, r.reservationNumber")
    List<Stay> findWithReservationAndGuest(
            @Param("stayStatus") StayStatus stayStatus,
            @Param("reservationStatus") ReservationStatus reservationStatus);

    /**
     * Like {@link #findWithReservationAndGuest} but only Stays whose planned check-out date is on or before a date.
     *
     * @param stayStatus Stay status
     * @param reservationStatus Reservation status
     * @param checkOutOnOrBefore latest planned check-out date included
     * @return matching Stays with Reservation and Guest initialized
     */
    @Query("SELECT s FROM Stay s JOIN FETCH s.reservation r JOIN FETCH r.guest "
            + "WHERE s.status = :stayStatus AND r.status = :reservationStatus AND r.checkOutDate <= :checkOutOnOrBefore "
            + "ORDER BY r.checkOutDate, r.reservationNumber")
    List<Stay> findWithReservationAndGuestDueBy(
            @Param("stayStatus") StayStatus stayStatus,
            @Param("reservationStatus") ReservationStatus reservationStatus,
            @Param("checkOutOnOrBefore") LocalDate checkOutOnOrBefore);

    /**
     * Finds which of the given Reservations already have a Stay, in one query.
     *
     * @param reservationIds Reservation identifiers
     * @return identifiers of Reservations that have a Stay
     */
    @Query("SELECT s.reservation.id FROM Stay s WHERE s.reservation.id IN :reservationIds")
    List<UUID> findReservationIdsWithStay(@Param("reservationIds") Collection<UUID> reservationIds);
}
