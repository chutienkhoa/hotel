package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.StayExtension;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads and stores Stay extension events. */
public interface StayExtensionRepository extends JpaRepository<StayExtension, UUID> {

    /**
     * Returns the highest sequence number recorded for a Stay, or {@code 0} when it was never extended.
     *
     * @param stayId Stay identifier
     * @return the last sequence number
     */
    @Query("SELECT COALESCE(MAX(e.sequenceNo), 0) FROM StayExtension e WHERE e.stay.id = :stayId")
    int findLastSequenceNo(@Param("stayId") UUID stayId);

    /**
     * Lists the extension events of a Stay in chain order.
     *
     * @param stayId Stay identifier
     * @return events ordered by sequence
     */
    @Query("SELECT e FROM StayExtension e WHERE e.stay.id = :stayId ORDER BY e.sequenceNo")
    List<StayExtension> findByStayId(@Param("stayId") UUID stayId);
}
