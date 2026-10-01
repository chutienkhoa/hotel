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
import java.time.LocalDate;
import java.util.UUID;

/** Represents an Expense v1 record with an explicitly controlled lifecycle. */
@Entity
@Table(name = "expense")
public class Expense extends AuditedEntity {

    public static final String CURRENCY_VND = "VND";

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private ExpenseCategory category;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private ExpensePaymentMethod paymentMethod;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseStatus status;

    @Column(name = "approved_by")
    private UUID approvedBy;

    /** Creates an empty Expense instance for JPA. */
    protected Expense() {}

    /**
     * Creates a draft Expense with backend-controlled identity, currency, and lifecycle fields.
     *
     * @param category required read-only Expense category
     * @param amount positive Expense amount
     * @param expenseDate client-selected business date
     * @param paymentMethod selected Expense payment method
     * @param description optional Expense description
     * @return new draft Expense
     */
    public static Expense create(
            ExpenseCategory category,
            BigDecimal amount,
            LocalDate expenseDate,
            ExpensePaymentMethod paymentMethod,
            String description) {
        Expense expense = new Expense();
        expense.id = UUID.randomUUID();
        expense.currency = CURRENCY_VND;
        expense.status = ExpenseStatus.DRAFT;
        expense.approvedBy = null;
        expense.updateDraft(category, amount, expenseDate, paymentMethod, description);
        return expense;
    }

    /**
     * Updates the approved client-controlled business fields while this Expense remains a draft.
     *
     * @param category required read-only Expense category
     * @param amount positive Expense amount
     * @param expenseDate client-selected business date
     * @param paymentMethod selected Expense payment method
     * @param description optional Expense description
     * @throws IllegalStateException if the Expense is no longer a draft
     */
    public void updateDraft(
            ExpenseCategory category,
            BigDecimal amount,
            LocalDate expenseDate,
            ExpensePaymentMethod paymentMethod,
            String description) {
        if (status != null && status != ExpenseStatus.DRAFT) {
            throw new IllegalStateException("Only draft Expenses can be updated");
        }
        this.category = category;
        this.amount = amount;
        this.expenseDate = expenseDate;
        this.paymentMethod = paymentMethod;
        this.description = description;
    }

    /**
     * Submits this draft Expense for review.
     *
     * @throws IllegalStateException if this Expense is not a draft
     */
    public void submit() {
        transition(ExpenseStatus.DRAFT, ExpenseStatus.SUBMITTED);
    }

    /**
     * Approves this submitted Expense and records the authenticated approver.
     *
     * @param approverId authenticated application-user identifier
     * @throws IllegalStateException if this Expense is not submitted
     */
    public void approve(UUID approverId) {
        transition(ExpenseStatus.SUBMITTED, ExpenseStatus.APPROVED);
        approvedBy = approverId;
    }

    /**
     * Rejects this submitted Expense.
     *
     * @throws IllegalStateException if this Expense is not submitted
     */
    public void reject() {
        transition(ExpenseStatus.SUBMITTED, ExpenseStatus.REJECTED);
    }

    /**
     * Posts this approved Expense without creating an AccountingEntry in Expense v1.
     *
     * @throws IllegalStateException if this Expense is not approved
     */
    public void post() {
        transition(ExpenseStatus.APPROVED, ExpenseStatus.POSTED);
    }

    /**
     * Returns the backend-generated technical identifier.
     *
     * @return Expense identifier
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the read-only category assigned to this Expense.
     *
     * @return Expense category
     */
    public ExpenseCategory getCategory() {
        return category;
    }

    /**
     * Returns the recorded Expense amount.
     *
     * @return positive Expense amount
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Returns the fixed backend-controlled Expense currency.
     *
     * @return {@code VND}
     */
    public String getCurrency() {
        return currency;
    }

    /**
     * Returns the client-selected business date of this Expense.
     *
     * @return Expense business date
     */
    public LocalDate getExpenseDate() {
        return expenseDate;
    }

    /**
     * Returns the Expense-specific payment method.
     *
     * @return selected Expense payment method
     */
    public ExpensePaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    /**
     * Returns the optional Expense description.
     *
     * @return description, or {@code null} when omitted
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the current controlled lifecycle status.
     *
     * @return Expense status
     */
    public ExpenseStatus getStatus() {
        return status;
    }

    /**
     * Returns the backend-recorded approver after approval.
     *
     * @return approver identifier, or {@code null} before approval
     */
    public UUID getApprovedBy() {
        return approvedBy;
    }

    /**
     * Applies one approved Expense lifecycle transition.
     *
     * @param expectedStatus only permitted current status
     * @param targetStatus approved target status
     * @throws IllegalStateException if this Expense is not in the expected status
     */
    private void transition(ExpenseStatus expectedStatus, ExpenseStatus targetStatus) {
        if (status != expectedStatus) {
            throw new IllegalStateException("Invalid Expense state transition");
        }
        status = targetStatus;
    }
}
