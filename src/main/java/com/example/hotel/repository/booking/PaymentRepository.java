package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.Payment;
import com.example.hotel.entity.booking.PaymentMethod;
import com.example.hotel.entity.booking.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Provides persistence, stable listing, and paid-total aggregation for Payments. */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /**
     * Finds Payments belonging to one Stay in stable creation order.
     *
     * @param stayId owning Stay identifier
     * @return ordered Payments for the Stay
     */
    List<Payment> findByStayIdOrderByCreatedAtAscIdAsc(UUID stayId);

    /**
     * Locks a Payment before its explicit state transition.
     *
     * @param id Payment identifier
     * @return locked Payment when it exists
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Sums Payment applied amounts (already denominated in the owning Reservation's currency) in
     * one status for a Stay.
     *
     * @param stayId owning Stay identifier
     * @param status Payment status included in the sum
     * @return total applied amount, or zero when no matching Payments exist
     */
    @Query(
            "SELECT COALESCE(SUM(p.appliedAmount), 0) "
                    + "FROM Payment p "
                    + "WHERE p.stay.id = :stayId "
                    + "AND p.status = :status")
    BigDecimal sumAppliedAmountByStayIdAndStatus(
            @Param("stayId") UUID stayId, @Param("status") PaymentStatus status);

    /**
     * Finds the Payments of the given statuses paid in {@code [start, endExclusive)}, reaching the Reservation
     * number and Guest through the owning Reservation (so prepayments received before check-in are included), ordered by payment time then id.
     *
     * @param statuses statuses to include
     * @param start first Instant included
     * @param endExclusive first Instant excluded
     * @return one row per Payment
     */
    @Query(
            "SELECT new com.example.hotel.repository.booking.PaymentExportRow("
                    + "p.paidAt, res.reservationNumber, g.firstName, g.lastName, p.method, p.reference, "
                    + "p.amount, p.currency, p.status) "
                    + "FROM Payment p JOIN p.reservation res JOIN res.guest g "
                    + "WHERE p.status IN :statuses AND p.paidAt >= :start AND p.paidAt < :endExclusive "
                    + "ORDER BY p.paidAt, p.id")
    List<PaymentExportRow> findExportRowsPaidWithin(
            @Param("statuses") java.util.Collection<PaymentStatus> statuses,
            @Param("start") java.time.Instant start,
            @Param("endExclusive") java.time.Instant endExclusive);

    /**
     * Sums the applied amount of Payments with one status per Stay for many Stays in one grouped query. Stays
     * without matching Payments are absent.
     *
     * @param stayIds Stay identifiers
     * @param status Payment status included in the sums
     * @return one row per Stay that has matching Payments
     */
    @Query("SELECT new com.example.hotel.repository.booking.StayAmountRow(p.stay.id, SUM(p.appliedAmount)) "
            + "FROM Payment p WHERE p.stay.id IN :stayIds AND p.status = :status GROUP BY p.stay.id")
    List<StayAmountRow> sumAppliedAmountByStayIdInAndStatus(
            @Param("stayIds") Collection<UUID> stayIds, @Param("status") PaymentStatus status);

    /**
     * Sums the applied amount of a Reservation's ACTIVE prepayments: PAID and not yet attached to a Stay.
     *
     * @param reservationId Reservation identifier
     * @return the active prepayment total in the Reservation currency (zero when none)
     */
    @Query("SELECT COALESCE(SUM(p.appliedAmount), 0) FROM Payment p WHERE p.reservation.id = :reservationId "
            + "AND p.stay IS NULL AND p.status = com.example.hotel.entity.booking.PaymentStatus.PAID")
    BigDecimal sumActivePrepaymentAppliedAmount(@Param("reservationId") UUID reservationId);

    /**
     * Loads and locks every active prepayment of a Reservation in one query (check-in application).
     *
     * @param reservationId Reservation identifier
     * @return the active prepayments
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.reservation.id = :reservationId AND p.stay IS NULL "
            + "AND p.status = com.example.hotel.entity.booking.PaymentStatus.PAID ORDER BY p.createdAt, p.id")
    List<Payment> findActivePrepaymentsForUpdate(@Param("reservationId") UUID reservationId);

    /**
     * Lists a Reservation's prepayments (no Stay yet) in creation order, PAID and REFUNDED, in one query.
     *
     * @param reservationId Reservation identifier
     * @return the prepayments
     */
    @Query("SELECT p FROM Payment p WHERE p.reservation.id = :reservationId AND p.stay IS NULL "
            + "ORDER BY p.createdAt, p.id")
    List<Payment> findPrepaymentsByReservationId(@Param("reservationId") UUID reservationId);

    /**
     * Tells whether the Reservation already has a live Payment with the same method and reference (duplicate guard).
     *
     * @param reservationId Reservation identifier
     * @param method payment method
     * @param reference normalized non-blank reference
     * @param ignoredStatuses statuses that do not count (FAILED, REFUNDED)
     * @return {@code true} when a live duplicate exists
     */
    @Query("SELECT COUNT(p) > 0 FROM Payment p WHERE p.reservation.id = :reservationId AND p.method = :method "
            + "AND TRIM(p.reference) = :reference AND p.status NOT IN :ignoredStatuses")
    boolean existsLiveWithReference(
            @Param("reservationId") UUID reservationId,
            @Param("method") PaymentMethod method,
            @Param("reference") String reference,
            @Param("ignoredStatuses") java.util.Collection<PaymentStatus> ignoredStatuses);
}
