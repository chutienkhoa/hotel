package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.Charge;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Provides persistence operations for Charges recorded against a Stay. */
public interface ChargeRepository extends JpaRepository<Charge, UUID> {

    /**
     * Finds the Charges of one Stay in a stable chronological order.
     *
     * @param stayId owning Stay identifier
     * @return Charges ordered by charge timestamp and identifier
     */
    List<Charge> findByStayIdOrderByChargedAtAscIdAsc(UUID stayId);

    /**
     * Sums the ACTIVE recorded Charge amounts for one Stay without calculating an outstanding
     * balance. A VOIDED Charge is an erroneous record and never counts toward Total Charges; this is
     * the single authoritative place that filter is applied, so every caller (Stay balance, Payment
     * overpayment validation, Front Desk) observes it automatically.
     *
     * @param stayId owning Stay identifier
     * @return total ACTIVE Charge amount, or zero when the Stay has no ACTIVE Charges
     */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM Charge c "
            + "WHERE c.stay.id = :stayId AND c.status = com.example.hotel.entity.booking.ChargeStatus.ACTIVE")
    BigDecimal sumAmountByStayId(@Param("stayId") UUID stayId);

    /**
     * Sums the ACTIVE Charge amounts of one Stay that are ROOM Charges (the accommodation created by check-in and Stay
     * Extension), using the same ACTIVE filter as {@link #sumAmountByStayId}.
     *
     * @param stayId owning Stay identifier
     * @return total ACTIVE ROOM Charge amount, or zero when there is none
     */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM Charge c "
            + "WHERE c.stay.id = :stayId AND c.status = com.example.hotel.entity.booking.ChargeStatus.ACTIVE "
            + "AND c.type = com.example.hotel.entity.booking.ChargeType.ROOM")
    BigDecimal sumRoomAmountByStayId(@Param("stayId") UUID stayId);

    /**
     * Sums the ACTIVE Charge amounts of one Stay that are not ROOM Charges (manually recorded additional charges),
     * using the same ACTIVE filter as {@link #sumAmountByStayId}.
     *
     * @param stayId owning Stay identifier
     * @return total ACTIVE non-ROOM Charge amount, or zero when there is none
     */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM Charge c "
            + "WHERE c.stay.id = :stayId AND c.status = com.example.hotel.entity.booking.ChargeStatus.ACTIVE "
            + "AND c.type <> com.example.hotel.entity.booking.ChargeType.ROOM")
    BigDecimal sumAdditionalAmountByStayId(@Param("stayId") UUID stayId);

    /**
     * Sums ACTIVE Charge amounts per Stay for many Stays in one grouped query. Stays without ACTIVE
     * Charges are absent.
     *
     * @param stayIds Stay identifiers
     * @return one row per Stay that has ACTIVE Charges
     */
    @Query("SELECT new com.example.hotel.repository.booking.StayAmountRow(c.stay.id, SUM(c.amount)) "
            + "FROM Charge c WHERE c.stay.id IN :stayIds "
            + "AND c.status = com.example.hotel.entity.booking.ChargeStatus.ACTIVE GROUP BY c.stay.id")
    List<StayAmountRow> sumAmountByStayIdIn(@Param("stayIds") Collection<UUID> stayIds);

    /**
     * Loads every Charge of a Stay (both ACTIVE and VOIDED) with its original-room source in one
     * query (reconciliation).
     *
     * @param stayId Stay identifier
     * @return the Stay's Charges
     */
    @Query("SELECT c FROM Charge c LEFT JOIN FETCH c.sourceReservationRoom WHERE c.stay.id = :stayId")
    List<Charge> findByStayIdWithSource(@Param("stayId") UUID stayId);

    /**
     * Locks a Charge before applying its explicit ACTIVE-to-VOIDED transition.
     *
     * @param id Charge identifier
     * @return locked Charge when it exists
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Charge c WHERE c.id = :id")
    Optional<Charge> findByIdForUpdate(@Param("id") UUID id);
}
