package com.example.hotel.entity.booking;

import com.example.hotel.entity.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Represents one recorded financial charge belonging to a stay. */
@Entity
@Table(name = "charge")
public class Charge extends AuditedEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stay_id", nullable = false)
    private Stay stay;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChargeType type;

    private String description;

    @Column(precision = 19, scale = 6)
    private BigDecimal quantity;

    @Column(name = "unit_price", precision = 19, scale = 6)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal amount;

    @Column(name = "charged_at", nullable = false)
    private Instant chargedAt;

    /** The booked room an ORIGINAL check-in ROOM charge was created from; {@code null} for every other charge. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_reservation_room_id")
    private ReservationRoom sourceReservationRoom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChargeStatus status;

    @Column(name = "void_reason")
    private String voidReason;

    /** Creates an empty Charge for JPA. */
    protected Charge() {}

    /**
     * Creates the ORIGINAL check-in ROOM Charge of one booked room, linked to its immutable {@link ReservationRoom}
     * source. The amount is copied from the ReservationRoom snapshot; extension charges never use this factory.
     *
     * @param stay owning stay
     * @param source booked room the charge is created from
     * @param description charge description
     * @return the new ROOM charge
     */
    public static Charge createOriginalRoomCharge(Stay stay, ReservationRoom source, String description, BigDecimal nights) {
        Charge charge = create(
                stay, ChargeType.ROOM, description, nights, source.getNightlyRate(), source.getTotalAmount());
        charge.sourceReservationRoom = source;
        return charge;
    }

    /**
     * Creates a Charge v1 record with server-owned identity and charge timestamp values.
     *
     * @param stay owning stay
     * @param type supported Charge v1 type
     * @param description optional charge description
     * @param quantity optional descriptive quantity
     * @param unitPrice optional descriptive unit price
     * @param amount authoritative recorded amount
     * @return the new charge
     */
    public static Charge create(
            Stay stay,
            ChargeType type,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal amount) {
        Charge charge = new Charge();
        charge.id = UUID.randomUUID();
        charge.stay = stay;
        charge.type = type;
        charge.description = description;
        charge.quantity = quantity;
        charge.unitPrice = unitPrice;
        charge.amount = amount;
        charge.chargedAt = Instant.now();
        charge.status = ChargeStatus.ACTIVE;
        return charge;
    }

    /**
     * Voids this Charge: it stops counting toward Total Charges but the row is never edited or
     * deleted. Only a manual, non-ROOM Charge can ever reach this method; the automatic ROOM Charge
     * created at check-in or Stay Extension has no correction path in V1. {@code updatedAt}/
     * {@code updatedBy} (set via {@link #audit(UUID)}) remain the authoritative void timestamp/user,
     * since VOIDED is a terminal status, mirroring {@code Payment#refund}.
     *
     * @param reason non-blank staff-supplied reason; validated by the caller before this transition
     *     is attempted
     * @throws IllegalStateException if this Charge is a ROOM charge or is not currently ACTIVE
     */
    public void voidCharge(String reason) {
        if (type == ChargeType.ROOM) {
            throw new IllegalStateException("ROOM charges cannot be voided");
        }
        if (status != ChargeStatus.ACTIVE) {
            throw new IllegalStateException("Only an active charge can be voided");
        }
        status = ChargeStatus.VOIDED;
        voidReason = reason;
    }

    /**
     * Returns the booked room this ORIGINAL ROOM charge was created from.
     *
     * @return the source ReservationRoom, or {@code null}
     */
    public ReservationRoom getSourceReservationRoom() {
        return sourceReservationRoom;
    }

    /**
     * Returns the current Charge lifecycle status.
     *
     * @return Charge status
     */
    public ChargeStatus getStatus() {
        return status;
    }

    /**
     * Returns the reason recorded when this Charge was voided.
     *
     * @return the void reason, or {@code null} before this Charge is voided
     */
    public String getVoidReason() {
        return voidReason;
    }

    /**
     * Returns the backend-generated technical identifier.
     *
     * @return the charge identifier
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the stay to which this Charge belongs.
     *
     * @return the owning stay
     */
    public Stay getStay() {
        return stay;
    }

    /**
     * Returns the Charge classification.
     *
     * @return the charge type
     */
    public ChargeType getType() {
        return type;
    }

    /**
     * Returns the optional description supplied for this Charge.
     *
     * @return the description, or {@code null} when absent
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the optional descriptive quantity.
     *
     * @return the quantity, or {@code null} when absent
     */
    public BigDecimal getQuantity() {
        return quantity;
    }

    /**
     * Returns the optional descriptive unit price.
     *
     * @return the unit price, or {@code null} when absent
     */
    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    /**
     * Returns the authoritative recorded amount.
     *
     * @return the charge amount
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Returns the backend-recorded charge creation time.
     *
     * @return the charge timestamp
     */
    public Instant getChargedAt() {
        return chargedAt;
    }
}
