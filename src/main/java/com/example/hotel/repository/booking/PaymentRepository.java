package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.Payment;
import com.example.hotel.entity.booking.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
     * number and Guest through the owning Stay, ordered by payment time then id.
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
                    + "FROM Payment p JOIN p.stay s JOIN s.reservation res JOIN res.guest g "
                    + "WHERE p.status IN :statuses AND p.paidAt >= :start AND p.paidAt < :endExclusive "
                    + "ORDER BY p.paidAt, p.id")
    List<PaymentExportRow> findExportRowsPaidWithin(
            @Param("statuses") java.util.Collection<PaymentStatus> statuses,
            @Param("start") java.time.Instant start,
            @Param("endExclusive") java.time.Instant endExclusive);
}
