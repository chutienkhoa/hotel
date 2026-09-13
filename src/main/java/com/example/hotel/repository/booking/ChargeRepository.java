package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.Charge;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * Sums all recorded Charge amounts for one Stay without calculating an outstanding balance.
     *
     * @param stayId owning Stay identifier
     * @return total Charge amount, or zero when the Stay has no Charges
     */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM Charge c WHERE c.stay.id = :stayId")
    BigDecimal sumAmountByStayId(@Param("stayId") UUID stayId);
}
