package com.example.hotel.repository.customer;

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
     * Retrieves every Guest ordered by stable guest code. A Guest is a reusable customer profile, so a Guest is never
     * excluded from Reservation selection because of any historical or concurrent Reservation.
     *
     * @return all Guests ordered by guest code
     */
    List<Guest> findAllByOrderByGuestCodeAsc();

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
