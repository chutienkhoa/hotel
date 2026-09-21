package com.example.hotel.entity.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import com.example.hotel.entity.booking.Charge;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Represents non-room hotel revenue with a recorded-or-voided lifecycle. */
@Entity
@Table(name = "additional_revenue")
public class AdditionalRevenue extends AuditedEntity {

    public static final String CURRENCY_VND = "VND";
    static final String LINKED_MESSAGE = "Additional Revenue created from a folio Charge is system-managed";

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private AdditionalRevenueCategory category;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "revenue_date", nullable = false)
    private LocalDate revenueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private AdditionalRevenuePaymentMethod paymentMethod;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdditionalRevenueStatus status;

    /** The folio Charge this guest service revenue originates from; {@code null} for standalone revenue. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "charge_id")
    private Charge charge;

    @Column(name = "void_reason")
    private String voidReason;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by")
    private UUID voidedBy;

    /** Creates an empty instance for JPA. */
    protected AdditionalRevenue() {}

    /** Creates immediately recorded VND revenue. */
    public static AdditionalRevenue create(
            AdditionalRevenueCategory category,
            BigDecimal amount,
            LocalDate revenueDate,
            AdditionalRevenuePaymentMethod paymentMethod,
            String description) {
        AdditionalRevenue revenue = new AdditionalRevenue();
        revenue.id = UUID.randomUUID();
        revenue.currency = CURRENCY_VND;
        revenue.status = AdditionalRevenueStatus.RECORDED;
        revenue.updateRecorded(category, amount, revenueDate, paymentMethod, description);
        return revenue;
    }

    /**
     * Creates the system-managed guest service revenue of a folio Charge. The amount is the Charge amount, the date is
     * the supplied hotel-local date of the Charge, and no payment method is recorded (posting a Charge is not a
     * payment).
     *
     * @param category system category of the Charge type
     * @param charge originating folio Charge
     * @param revenueDate hotel-local date of the Charge
     * @param description traceable description
     * @return the linked revenue row
     */
    public static AdditionalRevenue createFromCharge(
            AdditionalRevenueCategory category, Charge charge, LocalDate revenueDate, String description) {
        AdditionalRevenue revenue = new AdditionalRevenue();
        revenue.id = UUID.randomUUID();
        revenue.currency = CURRENCY_VND;
        revenue.status = AdditionalRevenueStatus.RECORDED;
        revenue.category = category;
        revenue.amount = charge.getAmount();
        revenue.revenueDate = revenueDate;
        revenue.paymentMethod = null;
        revenue.description = description;
        revenue.charge = charge;
        return revenue;
    }

    /**
     * Tells whether this revenue is system-managed because it originates from a folio Charge.
     *
     * @return {@code true} when linked to a Charge
     */
    public boolean isChargeLinked() {
        return charge != null;
    }

    /** Updates client-controlled data while the revenue remains recorded. */
    public void updateRecorded(
            AdditionalRevenueCategory category,
            BigDecimal amount,
            LocalDate revenueDate,
            AdditionalRevenuePaymentMethod paymentMethod,
            String description) {
        if (charge != null) {
            throw new IllegalStateException(LINKED_MESSAGE);
        }
        if (status != null && status != AdditionalRevenueStatus.RECORDED) {
            throw new IllegalStateException("Only recorded Additional Revenue can be updated");
        }
        this.category = category;
        this.amount = amount;
        this.revenueDate = revenueDate;
        this.paymentMethod = paymentMethod;
        this.description = description;
    }

    /** Voids recorded revenue and records the backend-owned void metadata. */
    public void voidRevenue(String reason, Instant at, UUID by) {
        if (charge != null) {
            throw new IllegalStateException(LINKED_MESSAGE);
        }
        if (status != AdditionalRevenueStatus.RECORDED) {
            throw new IllegalStateException("Only recorded Additional Revenue can be voided");
        }
        status = AdditionalRevenueStatus.VOIDED;
        voidReason = reason;
        voidedAt = at;
        voidedBy = by;
    }

    public UUID getId() { return id; }
    public AdditionalRevenueCategory getCategory() { return category; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public LocalDate getRevenueDate() { return revenueDate; }
    public AdditionalRevenuePaymentMethod getPaymentMethod() { return paymentMethod; }
    public String getDescription() { return description; }
    public AdditionalRevenueStatus getStatus() { return status; }
    public Charge getCharge() { return charge; }
    public String getVoidReason() { return voidReason; }
    public Instant getVoidedAt() { return voidedAt; }
    public UUID getVoidedBy() { return voidedBy; }
}
