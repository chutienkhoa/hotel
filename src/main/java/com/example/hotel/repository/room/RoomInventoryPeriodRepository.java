package com.example.hotel.repository.room;

import com.example.hotel.entity.room.RoomInventoryOrigin;
import com.example.hotel.entity.room.RoomInventoryPeriod;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Provides persistence access to Room inventory history. */
public interface RoomInventoryPeriodRepository extends JpaRepository<RoomInventoryPeriod, UUID> {

    /**
     * Finds the open (current) inventory periods of a Room; a healthy Room has exactly one.
     *
     * @param roomId Room identifier
     * @return every period of the Room whose {@code effectiveTo} is null
     */
    @Query("SELECT p FROM RoomInventoryPeriod p WHERE p.room.id = :roomId AND p.effectiveTo IS NULL")
    List<RoomInventoryPeriod> findOpenByRoomId(@Param("roomId") UUID roomId);

    /**
     * Finds the earliest effective Instant among periods of the given origin.
     *
     * @param origin period origin to consider
     * @return the earliest {@code effectiveFrom}, or {@code null} when no such period exists
     */
    @Query("SELECT MIN(p.effectiveFrom) FROM RoomInventoryPeriod p WHERE p.origin = :origin")
    Instant findEarliestEffectiveFromByOrigin(@Param("origin") RoomInventoryOrigin origin);
}
