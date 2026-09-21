package com.example.hotel.entity.booking;

import com.example.hotel.entity.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One extension event of a CHECKED_IN Stay's planned check-out date. Events form a single chain per Stay: each
 * event's previous date equals the prior event's new date (or the original planned check-out for the first one).
 */
@Entity
@Table(name = "stay_extension")
public class StayExtension extends AuditedEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stay_id", nullable = false)
    private Stay stay;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "previous_check_out_date", nullable = false)
    private LocalDate previousCheckOutDate;

    @Column(name = "new_check_out_date", nullable = false)
    private LocalDate newCheckOutDate;

    /** Creates an empty extension for JPA. */
    protected StayExtension() {}

    /**
     * Creates an extension event.
     *
     * @param stay extended Stay
     * @param sequenceNo 1-based position in the Stay's extension chain
     * @param previousCheckOutDate planned check-out before the extension
     * @param newCheckOutDate planned check-out after the extension
     * @throws IllegalArgumentException when the new date is not after the previous date
     */
    public StayExtension(Stay stay, int sequenceNo, LocalDate previousCheckOutDate, LocalDate newCheckOutDate) {
        if (!newCheckOutDate.isAfter(previousCheckOutDate)) {
            throw new IllegalArgumentException("Extension must move the planned check-out forward");
        }
        this.id = UUID.randomUUID();
        this.stay = stay;
        this.sequenceNo = sequenceNo;
        this.previousCheckOutDate = previousCheckOutDate;
        this.newCheckOutDate = newCheckOutDate;
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
     * Returns the extended Stay.
     *
     * @return the Stay
     */
    public Stay getStay() {
        return stay;
    }

    /**
     * Returns the position in the Stay's extension chain.
     *
     * @return the 1-based sequence number
     */
    public int getSequenceNo() {
        return sequenceNo;
    }

    /**
     * Returns the planned check-out before this extension (inclusive start of the added period).
     *
     * @return the previous planned check-out date
     */
    public LocalDate getPreviousCheckOutDate() {
        return previousCheckOutDate;
    }

    /**
     * Returns the planned check-out after this extension (exclusive end of the added period).
     *
     * @return the new planned check-out date
     */
    public LocalDate getNewCheckOutDate() {
        return newCheckOutDate;
    }
}
