package com.example.hotel.entity.booking;

import com.example.hotel.entity.common.AuditedEntity;
import com.example.hotel.entity.room.Room;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The accommodation and pricing snapshot of one room lineage for one extension event. The original
 * {@link ReservationRoom} is the lineage and rate anchor; {@code room} is the Room actually occupied when the
 * extension was made and is never re-derived. The posted ROOM {@link Charge} is referenced.
 */
@Entity
@Table(name = "stay_extension_room")
public class StayExtensionRoom extends AuditedEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stay_extension_id", nullable = false)
    private StayExtension extension;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "original_reservation_room_id", nullable = false)
    private ReservationRoom originalReservationRoom;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "nightly_rate", nullable = false, precision = 19, scale = 6)
    private BigDecimal nightlyRate;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "charge_id", nullable = false)
    private Charge charge;

    /** Creates an empty line for JPA. */
    protected StayExtensionRoom() {}

    /**
     * Creates the line for one lineage.
     *
     * @param extension owning extension event
     * @param originalReservationRoom lineage and rate anchor
     * @param room Room occupied at the moment of the extension
     * @param nightlyRate snapshot of the extension nightly rate
     * @param amount snapshot of {@code nightlyRate x added nights}
     * @param charge the posted ROOM Charge
     */
    public StayExtensionRoom(
            StayExtension extension,
            ReservationRoom originalReservationRoom,
            Room room,
            BigDecimal nightlyRate,
            BigDecimal amount,
            Charge charge) {
        this.id = UUID.randomUUID();
        this.extension = extension;
        this.originalReservationRoom = originalReservationRoom;
        this.room = room;
        this.fromDate = extension.getPreviousCheckOutDate();
        this.toDate = extension.getNewCheckOutDate();
        this.nightlyRate = nightlyRate;
        this.amount = amount;
        this.charge = charge;
    }

    /**
     * Returns the identifier.
     *
     * @return the identifier
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the owning extension event.
     *
     * @return the extension
     */
    public StayExtension getExtension() {
        return extension;
    }

    /**
     * Returns the lineage / original pricing anchor.
     *
     * @return the original ReservationRoom
     */
    public ReservationRoom getOriginalReservationRoom() {
        return originalReservationRoom;
    }

    /**
     * Returns the Room occupied when the extension was made.
     *
     * @return the historical Room snapshot
     */
    public Room getRoom() {
        return room;
    }

    /**
     * Returns the inclusive first night of the added period.
     *
     * @return the from date
     */
    public LocalDate getFromDate() {
        return fromDate;
    }

    /**
     * Returns the exclusive end of the added period.
     *
     * @return the to date
     */
    public LocalDate getToDate() {
        return toDate;
    }

    /**
     * Returns the nightly rate snapshot.
     *
     * @return the rate
     */
    public BigDecimal getNightlyRate() {
        return nightlyRate;
    }

    /**
     * Returns the amount snapshot.
     *
     * @return the amount
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Returns the posted ROOM Charge.
     *
     * @return the Charge
     */
    public Charge getCharge() {
        return charge;
    }
}
