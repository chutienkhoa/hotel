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
     * Sums Payment amounts in one status for a Stay.
     *
     * @param stayId owning Stay identifier
     * @param status Payment status included in the sum
     * @return total amount, or zero when no matching Payments exist
     */
    @Query(
            "SELECT COALESCE(SUM(p.amount), 0) "
                    + "FROM Payment p "
                    + "WHERE p.stay.id = :stayId "
                    + "AND p.status = :status")
    BigDecimal sumAmountByStayIdAndStatus(
            @Param("stayId") UUID stayId, @Param("status") PaymentStatus status);
}
