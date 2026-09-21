package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.query.Param;

/** Cung cấp truy vấn lưu trữ và kiểm tra xung đột reservation. */
public interface ReservationRepository
        extends JpaRepository<Reservation, UUID>, JpaSpecificationExecutor<Reservation> {

    /**
     * Loads every Reservation with its Guest eagerly fetched, avoiding a per-row lazy load.
     *
     * @return every Reservation, Guest included
     */
    @Override
    @EntityGraph(attributePaths = "guest")
    List<Reservation> findAll();

    /**
     * Loads one filtered, sorted page of Reservations with its Guest eagerly fetched, avoiding a
     * per-row lazy load. Room data is intentionally not fetch-joined here since it is a
     * collection association; batch-loading room numbers separately keeps this page free of
     * duplicate Reservation rows and incorrect pagination totals.
     *
     * @param spec the Specification containing only the supplied filters
     * @param pageable the requested page, size, and sort order
     * @return the matching Reservation page, Guest included
     */
    @Override
    @EntityGraph(attributePaths = "guest")
    Page<Reservation> findAll(Specification<Reservation> spec, Pageable pageable);

    /**
     * Batch-loads each Room number assigned to the supplied Reservations, avoiding an N+1 query
     * per row when rendering a Reservation list or detail page.
     *
     * @param reservationIds Reservation identifiers whose assigned rooms are loaded
     * @return reservation identifier and room number pairs, ordered by room number
     */
    @Query(
            "SELECT rr.reservation.id, rr.room.roomNumber FROM ReservationRoom rr "
                    + "WHERE rr.reservation.id IN :reservationIds "
                    + "ORDER BY rr.room.roomNumber")
    List<Object[]> findRoomNumbersByReservationIdIn(@Param("reservationIds") Collection<UUID> reservationIds);

    /**
     * Counts all Reservations grouped by their current lifecycle status.
     *
     * @return status and count rows in stable status order
     */
    @Query("SELECT r.status, COUNT(r) FROM Reservation r GROUP BY r.status ORDER BY r.status")
    List<Object[]> countAllByStatus();

    /**
     * Counts Reservations by planned check-in year and month within a reporting period.
     *
     * @param startDate inclusive reporting-period start
     * @param endDateExclusive exclusive reporting-period end
     * @return year, month, and count rows in chronological order
     */
    @Query(
            "SELECT YEAR(r.checkInDate), MONTH(r.checkInDate), COUNT(r) "
                    + "FROM Reservation r "
                    + "WHERE r.checkInDate >= :startDate "
                    + "AND r.checkInDate < :endDateExclusive "
                    + "GROUP BY YEAR(r.checkInDate), MONTH(r.checkInDate) "
                    + "ORDER BY YEAR(r.checkInDate), MONTH(r.checkInDate)")
    List<Object[]> countByCheckInMonthWithin(
            @Param("startDate") LocalDate startDate,
            @Param("endDateExclusive") LocalDate endDateExclusive);

    /**
     * Counts assigned ReservationRoom rows by each Room's current RoomType within a reporting period.
     *
     * @param startDate inclusive reporting-period start
     * @param endDateExclusive exclusive reporting-period end
     * @return RoomType code, name, and assigned-room count rows in code order
     */
    @Query(
            "SELECT roomType.code, roomType.name, COUNT(rr) "
                    + "FROM ReservationRoom rr "
                    + "JOIN rr.room room "
                    + "JOIN room.roomType roomType "
                    + "WHERE rr.reservation.checkInDate >= :startDate "
                    + "AND rr.reservation.checkInDate < :endDateExclusive "
                    + "GROUP BY roomType.code, roomType.name "
                    + "ORDER BY roomType.code")
    List<Object[]> countBookedRoomsByRoomTypeWithin(
            @Param("startDate") LocalDate startDate,
            @Param("endDateExclusive") LocalDate endDateExclusive);

    /**
     * Counts Reservations by booking source within a planned check-in reporting period.
     *
     * @param startDate inclusive reporting-period start
     * @param endDateExclusive exclusive reporting-period end
     * @return source and count rows in source order
     */
    @Query(
            "SELECT r.source, COUNT(r) "
                    + "FROM Reservation r "
                    + "WHERE r.checkInDate >= :startDate "
                    + "AND r.checkInDate < :endDateExclusive "
                    + "GROUP BY r.source "
                    + "ORDER BY r.source")
    List<Object[]> countBySourceWithin(
            @Param("startDate") LocalDate startDate,
            @Param("endDateExclusive") LocalDate endDateExclusive);

    /**
     * Atomically allocates the next daily reservation number in the approved external format.
     *
     * @return the generated reservation number, or empty when the daily sequence is exhausted
     */
    @Query(
            value = """
                    INSERT INTO reservation_number_sequence (
                        reservation_date,
                        last_value
                    ) VALUES (
                        CURRENT_DATE,
                        1
                    )
                    ON CONFLICT (reservation_date)
                    DO UPDATE
                    SET last_value = reservation_number_sequence.last_value + 1
                    WHERE reservation_number_sequence.last_value < 999999
                    RETURNING CONCAT(
                        'R',
                        TO_CHAR(reservation_date, 'YYYYMMDD'),
                        '-',
                        LPAD(last_value::TEXT, 6, '0')
                    )
                    """,
            nativeQuery = true)
    Optional<String> allocateReservationNumber();

    /**
     * Finds the ReservationRoom pricing snapshots whose booked interval overlaps a month and whose
     * Reservation is in one of the given statuses, as a narrow projection (no entity graph).
     *
     * @param monthStart inclusive first day of the month
     * @param nextMonthStart exclusive first day of the next month
     * @param statuses eligible Reservation statuses
     * @return overlapping snapshot rows
     */
    @Query(
            "SELECT new com.example.hotel.repository.booking.ReservationRoomRevenueRow("
                    + "rr.id, rr.reservation.id, rr.checkInDate, rr.checkOutDate, rr.nightlyRate, "
                    + "rr.totalAmount, rr.reservation.currency, rr.reservation.status) "
                    + "FROM ReservationRoom rr "
                    + "WHERE rr.checkInDate < :nextMonthStart AND rr.checkOutDate > :monthStart "
                    + "AND rr.reservation.status IN :statuses "
                    + "ORDER BY rr.checkInDate, rr.id")
    List<ReservationRoomRevenueRow> findRoomRevenueRows(
            @Param("monthStart") LocalDate monthStart,
            @Param("nextMonthStart") LocalDate nextMonthStart,
            @Param("statuses") Collection<ReservationStatus> statuses);

    /**
     * Finds the Reservations whose check-in date lies in {@code [start, endExclusive)}, every status, as a
     * narrow projection ordered by check-in date then reservation number.
     *
     * @param start first check-in date included
     * @param endExclusive first check-in date excluded
     * @return one row per Reservation
     */
    @Query(
            "SELECT new com.example.hotel.repository.booking.ReservationExportRow("
                    + "r.id, r.reservationNumber, r.source, r.otaBookingReference, g.firstName, g.lastName, "
                    + "r.checkInDate, r.checkOutDate, r.totalAmount, r.currency, r.status) "
                    + "FROM Reservation r JOIN r.guest g "
                    + "WHERE r.checkInDate >= :start AND r.checkInDate < :endExclusive "
                    + "ORDER BY r.checkInDate, r.reservationNumber")
    List<ReservationExportRow> findExportRowsByCheckInWithin(
            @Param("start") LocalDate start, @Param("endExclusive") LocalDate endExclusive);

    /**
     * Finds the distinct RoomTypes booked by every Reservation whose check-in date lies in
     * {@code [start, endExclusive)}, in one query (no per-Reservation lookups), ordered by RoomType code.
     * The RoomType is resolved through the booked Room, as the existing booked-room analytics do.
     *
     * @param start first check-in date included
     * @param endExclusive first check-in date excluded
     * @return one row per (Reservation, RoomType)
     */
    @Query(
            "SELECT DISTINCT new com.example.hotel.repository.booking.ReservationRoomTypeRow("
                    + "rr.reservation.id, rt.code, rt.name) "
                    + "FROM ReservationRoom rr JOIN rr.room room JOIN room.roomType rt "
                    + "WHERE rr.reservation.checkInDate >= :start AND rr.reservation.checkInDate < :endExclusive "
                    + "ORDER BY rt.code")
    List<ReservationRoomTypeRow> findBookedRoomTypesByCheckInWithin(
            @Param("start") LocalDate start, @Param("endExclusive") LocalDate endExclusive);

    /**
     * Loads a Reservation under a pessimistic write lock, for operations that must serialize with check-in,
     * cancellation and other reassignments of the same Reservation.
     *
     * @param id Reservation identifier
     * @return the locked Reservation, if it exists
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Finds, per Room, the earliest check-in date on or after a date among Reservations that are still to arrive
     * (only the given statuses), in one grouped query.
     *
     * @param from inclusive earliest arrival date (the hotel current date)
     * @param statuses Reservation statuses that represent an upcoming arrival
     * @return one row per Room that has a qualifying arrival
     */
    @Query(
            "SELECT new com.example.hotel.repository.booking.RoomNextArrivalRow(rr.room.id, MIN(rr.checkInDate)) "
                    + "FROM ReservationRoom rr "
                    + "WHERE rr.reservation.status IN :statuses "
                    + "AND rr.checkInDate >= :from "
                    + "GROUP BY rr.room.id")
    List<RoomNextArrivalRow> findNextArrivalsFrom(
            @Param("from") LocalDate from, @Param("statuses") Collection<ReservationStatus> statuses);

    /**
     * Loads Reservations of a status whose check-in date is on or before a date, with the Guest, in one query,
     * ordered by check-in date then Reservation number.
     *
     * @param status Reservation status
     * @param checkInOnOrBefore latest check-in date included
     * @return matching Reservations with Guest initialized
     */
    @Query("SELECT r FROM Reservation r JOIN FETCH r.guest "
            + "WHERE r.status = :status AND r.checkInDate <= :checkInOnOrBefore "
            + "ORDER BY r.checkInDate, r.reservationNumber")
    List<Reservation> findByStatusAndCheckInOnOrBefore(
            @Param("status") ReservationStatus status, @Param("checkInOnOrBefore") LocalDate checkInOnOrBefore);

    /**
     * Loads the booked Rooms (with RoomType initialized) of many Reservations in one query, as
     * {@code [reservationId, Room]} pairs ordered by room number.
     *
     * @param reservationIds Reservation identifiers
     * @return reservation identifier and Room pairs
     */
    @Query("SELECT rr.reservation.id, room FROM ReservationRoom rr JOIN rr.room room JOIN FETCH room.roomType "
            + "WHERE rr.reservation.id IN :reservationIds ORDER BY room.roomNumber")
    List<Object[]> findBookedRoomsByReservationIdIn(@Param("reservationIds") Collection<UUID> reservationIds);
}
