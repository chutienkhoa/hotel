package com.example.hotel.repository.customer;

import com.example.hotel.entity.booking.ReservationStatus;
import com.example.hotel.entity.customer.Guest;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Cung cấp thao tác lưu trữ cho khách. */
public interface GuestRepository extends JpaRepository<Guest, UUID>, JpaSpecificationExecutor<Guest> {

    /**
     * Retrieves Guests eligible for a new Reservation, excluding any Guest with a completed
     * Reservation.
     *
     * @param checkedOutStatus completed Reservation status that excludes its Guest
     * @return eligible Guests ordered by stable guest code
     */
    @Query(
            "SELECT g FROM Guest g "
                    + "WHERE NOT EXISTS ("
                    + "SELECT r FROM Reservation r "
                    + "WHERE r.guest = g AND r.status = :checkedOutStatus"
                    + ") "
                    + "ORDER BY g.guestCode")
    List<Guest> findAllWithoutReservationStatus(
            @Param("checkedOutStatus") ReservationStatus checkedOutStatus);

    /**
     * Determines whether a guest code is already assigned to an existing guest.
     *
     * @param guestCode generated guest code to check
     * @return {@code true} when the code is already in use
     */
    boolean existsByGuestCode(String guestCode);

    /**
     * Retrieves the next numeric value from the PostgreSQL guest-code sequence.
     *
     * @return the next sequence number
     */
    @Query(value = "SELECT nextval('guest_code_sequence')", nativeQuery = true)
    long nextGuestCodeSequence();
}
