package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.Charge;
import java.math.BigDecimal;
import java.util.List;
import java.util.Collection;
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

    /**
     * Sums Charge amounts per Stay for many Stays in one grouped query. Stays without Charges are absent.
     *
     * @param stayIds Stay identifiers
     * @return one row per Stay that has Charges
     */
    @Query("SELECT new com.example.hotel.repository.booking.StayAmountRow(c.stay.id, SUM(c.amount)) "
            + "FROM Charge c WHERE c.stay.id IN :stayIds GROUP BY c.stay.id")
    List<StayAmountRow> sumAmountByStayIdIn(@Param("stayIds") Collection<UUID> stayIds);
}
