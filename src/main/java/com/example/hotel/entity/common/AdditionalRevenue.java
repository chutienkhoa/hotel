package com.example.hotel.entity.common;

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
import java.time.LocalDate;
import java.util.UUID;

/** Represents non-room hotel revenue with a recorded-or-voided lifecycle. */
@Entity
@Table(name = "additional_revenue")
public class AdditionalRevenue extends AuditedEntity {

    public static final String CURRENCY_VND = "VND";

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
    @Column(name = "payment_method", nullable = false)
    private AdditionalRevenuePaymentMethod paymentMethod;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdditionalRevenueStatus status;

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

    /** Updates client-controlled data while the revenue remains recorded. */
    public void updateRecorded(
            AdditionalRevenueCategory category,
            BigDecimal amount,
            LocalDate revenueDate,
            AdditionalRevenuePaymentMethod paymentMethod,
            String description) {
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
    public String getVoidReason() { return voidReason; }
    public Instant getVoidedAt() { return voidedAt; }
    public UUID getVoidedBy() { return voidedBy; }
}
