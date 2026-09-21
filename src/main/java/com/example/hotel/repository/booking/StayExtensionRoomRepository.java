package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.booking.StayExtensionRoom;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads and stores the per-lineage lines of Stay extensions. */
public interface StayExtensionRoomRepository extends JpaRepository<StayExtensionRoom, UUID> {

    /**
     * Loads the extension lines of one Reservation's Stay with room and event, in one query (no per-line lookups).
     *
     * @param reservationId Reservation identifier
     * @return lines ordered by sequence then room number
     */
    @Query("SELECT l FROM StayExtensionRoom l JOIN FETCH l.extension e JOIN FETCH l.room room "
            + "WHERE e.stay.reservation.id = :reservationId ORDER BY e.sequenceNo, room.roomNumber")
    List<StayExtensionRoom> findByReservationId(@Param("reservationId") UUID reservationId);

    /**
     * Narrow revenue projection of extension lines overlapping a month whose Reservation is in an eligible status.
     *
     * @param monthStart inclusive first day of the month
     * @param nextMonthStart exclusive first day of the next month
     * @param statuses eligible Reservation statuses
     * @return overlapping extension lines
     */
    @Query("SELECT new com.example.hotel.repository.booking.StayExtensionRevenueRow("
            + "l.id, l.originalReservationRoom.id, e.stay.reservation.id, l.fromDate, l.toDate, l.nightlyRate, "
            + "l.amount, e.stay.reservation.currency, e.stay.reservation.status) "
            + "FROM StayExtensionRoom l JOIN l.extension e "
            + "WHERE l.fromDate < :nextMonthStart AND l.toDate > :monthStart "
            + "AND e.stay.reservation.status IN :statuses ORDER BY l.fromDate, l.id")
    List<StayExtensionRevenueRow> findRevenueRows(
            @Param("monthStart") LocalDate monthStart,
            @Param("nextMonthStart") LocalDate nextMonthStart,
            @Param("statuses") Collection<ReservationStatus> statuses);

    /**
     * Loads the extension lines of one Stay with their Charge in one query (reconciliation).
     *
     * @param stayId Stay identifier
     * @return the Stay's extension lines
     */
    @Query("SELECT l FROM StayExtensionRoom l JOIN FETCH l.charge WHERE l.extension.stay.id = :stayId")
    List<StayExtensionRoom> findByStayIdWithCharge(@Param("stayId") UUID stayId);
}
