package com.example.hotel.repository.booking;

import com.example.hotel.entity.booking.ReservationGuest;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads the Accompanying Guest associations of Reservations. */
public interface ReservationGuestRepository extends JpaRepository<ReservationGuest, UUID> {

    /**
     * Loads the Accompanying Guests of one Reservation with their Guest profiles in a single query (never one query
     * per Guest), ordered by guest code.
     *
     * @param reservationId Reservation identifier
     * @return associations with the Guest initialized
     */
    @Query("SELECT rg FROM ReservationGuest rg JOIN FETCH rg.guest g "
            + "WHERE rg.reservation.id = :reservationId ORDER BY g.guestCode")
    List<ReservationGuest> findByReservationIdWithGuest(@Param("reservationId") UUID reservationId);
}
