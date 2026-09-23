package com.example.hotel.entity.room;

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
import java.time.Instant;
import java.util.UUID;

/**
 * One effective inventory state of one Room: the RoomType it had and whether it was removed from
 * sellable inventory, from {@code effectiveFrom} until {@code effectiveTo}. A period is a maximal
 * interval in which both values are unchanged; the open period ({@code effectiveTo == null}) is the
 * Room's current state. Closed periods are historical facts and are never edited; the only mutation
 * is closing the open period.
 *
 * <p>Instants are stored exactly as recorded. Reporting attributes a hotel night {@code d} to the
 * period whose hotel-date range {@code [localDate(effectiveFrom), localDate(effectiveTo))} contains
 * {@code d}, so the final state of a calendar date owns that night.
 */
@Entity
@Table(name = "room_inventory_period")
public class RoomInventoryPeriod extends AuditedEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false)
    private RoomType roomType;

    @Enumerated(EnumType.STRING)
    @Column(name = "unavailable_reason", length = 32)
    private RoomUnavailableReason unavailableReason;

    @Column(name = "reason")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RoomInventoryOrigin origin;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    protected RoomInventoryPeriod() {}

    /**
     * Opens a RECORDED period for a Room. A sellable period (no unavailable reason) never carries a
     * human-readable reason, regardless of what the caller passes, so the invariant enforced by the
     * database also holds in memory.
     *
     * @param room Room the period describes
     * @param roomType RoomType effective during the period
     * @param unavailableReason reason the Room is not sellable, or {@code null} when sellable
     * @param reason human-readable explanation for the unavailability, or {@code null}; ignored when
     *     {@code unavailableReason} is {@code null}
     * @param effectiveFrom real Instant the state became effective
     * @param user authenticated user recorded as creator
     */
    public RoomInventoryPeriod(
            Room room,
            RoomType roomType,
            RoomUnavailableReason unavailableReason,
            String reason,
            Instant effectiveFrom,
            UUID user) {
        id = UUID.randomUUID();
        this.room = room;
        this.roomType = roomType;
        this.unavailableReason = unavailableReason;
        this.reason = unavailableReason == null ? null : reason;
        this.origin = RoomInventoryOrigin.RECORDED;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = null;
        audit(user);
    }

    public UUID getId() {
        return id;
    }

    public Room getRoom() {
        return room;
    }

    public RoomType getRoomType() {
        return roomType;
    }

    public RoomUnavailableReason getUnavailableReason() {
        return unavailableReason;
    }

    /**
     * Returns the human-readable explanation recorded for this period's unavailability.
     *
     * @return the reason, or {@code null} when the period is sellable or none was recorded (for
     *     example a BOOTSTRAP period)
     */
    public String getReason() {
        return reason;
    }

    public RoomInventoryOrigin getOrigin() {
        return origin;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    /**
     * Tells whether the period is still the Room's current inventory state.
     *
     * @return {@code true} while {@code effectiveTo} is unset
     */
    public boolean isOpen() {
        return effectiveTo == null;
    }

    /**
     * Tells whether the Room is part of sellable inventory during this period.
     *
     * @return {@code true} when no unavailable reason applies
     */
    public boolean isSellable() {
        return unavailableReason == null;
    }

    /**
     * Closes this open period at the given Instant. The creator is preserved, so closing a
     * system-created BOOTSTRAP period never rewrites its missing creator.
     *
     * @param instant real Instant the state stopped being effective; must be after {@code effectiveFrom}
     * @param user authenticated user recorded as updater
     * @throws IllegalStateException if the period is already closed or the interval would be empty
     */
    public void close(Instant instant, UUID user) {
        if (effectiveTo != null) {
            throw new IllegalStateException("Room inventory period is already closed");
        }
        if (!instant.isAfter(effectiveFrom)) {
            throw new IllegalStateException("Room inventory period must end after it starts");
        }
        effectiveTo = instant;
        updatedBy = user;
    }
}
