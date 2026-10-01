package com.example.hotel.entity.booking;

import com.example.hotel.entity.common.AuditedEntity;
import com.example.hotel.entity.customer.Guest;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Associates an existing reusable Guest profile with a Reservation as an Accompanying Guest. It is part of the
 * Reservation aggregate and holds no copy of Guest data. The Primary Guest is never stored here (it stays in
 * {@code reservation.guest_id}), and the association is not tied to any room or Stay.
 */
@Entity
@Table(name = "reservation_guest")
public class ReservationGuest extends AuditedEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guest_id", nullable = false)
    private Guest guest;

    /** Creates an empty association for JPA. */
    protected ReservationGuest() {}

    /**
     * Creates the association. Only {@link Reservation} creates these, so its invariants always apply.
     *
     * @param reservation owning Reservation
     * @param guest the Accompanying Guest profile
     */
    ReservationGuest(Reservation reservation, Guest guest) {
        this.id = UUID.randomUUID();
        this.reservation = reservation;
        this.guest = guest;
    }

    /**
     * Returns the association identifier.
     *
     * @return the identifier
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the Accompanying Guest profile.
     *
     * @return the Guest
     */
    public Guest getGuest() {
        return guest;
    }
}
