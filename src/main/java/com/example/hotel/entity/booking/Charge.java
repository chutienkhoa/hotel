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

    /** Creates an empty Charge for JPA. */
    protected Charge() {}

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
        return charge;
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
