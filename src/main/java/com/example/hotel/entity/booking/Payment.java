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

/** Represents one Payment v1 record belonging to a Stay. */
@Entity
@Table(name = "payment")
public class Payment extends AuditedEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stay_id", nullable = false)
    private Stay stay;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentCurrency currency;

    @Column(name = "exchange_rate", precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    @Column(name = "applied_amount", nullable = false, precision = 19, scale = 6)
    private BigDecimal appliedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "paid_at")
    private Instant paidAt;

    private String reference;

    @Column(name = "refund_reason")
    private String refundReason;

    /** Creates an empty Payment for JPA. */
    protected Payment() {}

    /**
     * Creates a pending Payment with server-owned identity and lifecycle fields.
     *
     * <p>{@code amount}/{@code currency}/{@code exchangeRate}/{@code appliedAmount} form one
     * immutable financial snapshot: the caller must supply an already-validated, already-computed
     * {@code appliedAmount} (see {@code PaymentService}'s single authoritative calculation path) —
     * this factory performs no currency conversion of its own.</p>
     *
     * @param stay owning Stay
     * @param amount immutable amount actually received, in {@code currency}
     * @param currency currency the amount was actually received in
     * @param exchangeRate {@code null} for a same-currency Payment; otherwise the immutable
     *     "1 USD = exchangeRate VND" rate used to compute {@code appliedAmount}
     * @param appliedAmount immutable amount applied to the Folio, already converted to the owning
     *     Reservation's currency
     * @param method selected Payment method
     * @param reference optional external or manual reference
     * @return new pending Payment
     */
    public static Payment create(
            Stay stay,
            BigDecimal amount,
            PaymentCurrency currency,
            BigDecimal exchangeRate,
            BigDecimal appliedAmount,
            PaymentMethod method,
            String reference) {
        Payment payment = new Payment();
        payment.id = UUID.randomUUID();
        payment.stay = stay;
        payment.amount = amount;
        payment.currency = currency;
        payment.exchangeRate = exchangeRate;
        payment.appliedAmount = appliedAmount;
        payment.method = method;
        payment.status = PaymentStatus.PENDING;
        payment.paidAt = null;
        payment.reference = reference;
        return payment;
    }

    /**
     * Transitions this pending Payment to paid and records the supplied authoritative payment
     * time.
     *
     * @param paidAt backend-authoritative instant, derived from the injected server Clock, never
     *     from client input
     */
    public void markPaid(Instant paidAt) {
        transition(PaymentStatus.PENDING, PaymentStatus.PAID);
        this.paidAt = paidAt;
    }

    /** Transitions this pending Payment to failed without recording a payment time. */
    public void markFailed() {
        transition(PaymentStatus.PENDING, PaymentStatus.FAILED);
    }

    /**
     * Transitions this paid Payment to refunded while preserving its original financial snapshot
     * ({@code amount}, {@code currency}, {@code exchangeRate}, {@code appliedAmount}) and paid
     * time, and persists the required refund reason. {@code updatedAt}/{@code updatedBy} (set via
     * {@link #audit(UUID)}) remain the authoritative refund timestamp/user, since REFUNDED is a
     * terminal status.
     *
     * @param refundReason non-blank staff-supplied reason; validated by the caller before this
     *     transition is attempted
     */
    public void refund(String refundReason) {
        transition(PaymentStatus.PAID, PaymentStatus.REFUNDED);
        this.refundReason = refundReason;
    }

    /**
     * Returns the backend-generated Payment identifier.
     *
     * @return Payment identifier
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the Stay that owns this Payment.
     *
     * @return owning Stay
     */
    public Stay getStay() {
        return stay;
    }

    /**
     * Returns the immutable amount actually received from the guest, in {@link #getCurrency()}.
     *
     * @return Payment amount
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Returns the currency the Payment amount was actually received in.
     *
     * @return Payment currency
     */
    public PaymentCurrency getCurrency() {
        return currency;
    }

    /**
     * Returns the immutable "1 USD = exchangeRate VND" rate used to compute
     * {@link #getAppliedAmount()}.
     *
     * @return the exchange rate, or {@code null} for a same-currency Payment
     */
    public BigDecimal getExchangeRate() {
        return exchangeRate;
    }

    /**
     * Returns the immutable amount applied to the Folio, in the owning Reservation's currency.
     *
     * @return applied amount
     */
    public BigDecimal getAppliedAmount() {
        return appliedAmount;
    }

    /**
     * Returns the selected Payment method.
     *
     * @return Payment method
     */
    public PaymentMethod getMethod() {
        return method;
    }

    /**
     * Returns the current Payment lifecycle status.
     *
     * @return Payment status
     */
    public PaymentStatus getStatus() {
        return status;
    }

    /**
     * Returns the backend-recorded payment time.
     *
     * @return payment time, or {@code null} while not paid
     */
    public Instant getPaidAt() {
        return paidAt;
    }

    /**
     * Returns the optional Payment reference.
     *
     * @return reference, or {@code null} when absent
     */
    public String getReference() {
        return reference;
    }

    /**
     * Returns the reason recorded for a full refund.
     *
     * @return the refund reason, or {@code null} before this Payment is refunded
     */
    public String getRefundReason() {
        return refundReason;
    }

    /**
     * Applies one approved Payment state transition.
     *
     * @param expectedStatus only permitted current status
     * @param targetStatus approved target status
     * @throws IllegalStateException if the current status is not approved for the transition
     */
    private void transition(PaymentStatus expectedStatus, PaymentStatus targetStatus) {
        if (status != expectedStatus) {
            throw new IllegalStateException("Invalid payment state transition");
        }
        status = targetStatus;
    }
}
