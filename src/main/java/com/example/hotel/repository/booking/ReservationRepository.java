package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.Reservation;
import com.example.hotel.entity.booking.ReservationStatus;
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
     * Kiểm tra xem một phòng có reservation thuộc các trạng thái được chỉ định bị giao ngày hay
     * không.
     *
     * @param roomId định danh phòng
     * @param in ngày bắt đầu cần kiểm tra
     * @param out ngày kết thúc cần kiểm tra
     * @param statuses các trạng thái reservation được tính là đang chiếm phòng
     * @return {@code true} nếu tồn tại khoảng ngày giao nhau
     */
    @Query(
            "SELECT COUNT(rr) > 0 "
                    + "FROM ReservationRoom rr "
                    + "WHERE rr.room.id = :roomId "
                    + "AND rr.reservation.status IN :statuses "
                    + "AND rr.checkInDate < :out "
                    + "AND rr.checkOutDate > :in")
    boolean hasOverlap(
            @Param("roomId") UUID roomId,
            @Param("in") LocalDate in,
            @Param("out") LocalDate out,
            @Param("statuses") Collection<ReservationStatus> statuses);

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
                    + "rr.totalAmount, rr.reservation.currency) "
                    + "FROM ReservationRoom rr "
                    + "WHERE rr.checkInDate < :nextMonthStart AND rr.checkOutDate > :monthStart "
                    + "AND rr.reservation.status IN :statuses "
                    + "ORDER BY rr.checkInDate, rr.id")
    List<ReservationRoomRevenueRow> findRoomRevenueRows(
            @Param("monthStart") LocalDate monthStart,
            @Param("nextMonthStart") LocalDate nextMonthStart,
            @Param("statuses") Collection<ReservationStatus> statuses);
}
