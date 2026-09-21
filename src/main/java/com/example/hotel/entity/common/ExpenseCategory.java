package com.example.hotel.entity.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Represents configurable Expense v1 reference data used to classify Expenses. */
@Entity
@Table(name = "expense_category")
public class ExpenseCategory extends AuditedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    private String name;

    private String description;

    @Column(nullable = false)
    private boolean active;

    /** Creates an empty ExpenseCategory instance for JPA. */
    protected ExpenseCategory() {}

    /**
     * Creates a new active Expense category with an immutable technical code.
     *
     * @param code stable, normalized, unique technical code
     * @param name required display name
     * @param description optional description
     * @return the new active category
     */
    public static ExpenseCategory create(String code, String name, String description) {
        ExpenseCategory category = new ExpenseCategory();
        category.id = UUID.randomUUID();
        category.code = code;
        category.active = true;
        category.updateProfile(name, description);
        return category;
    }

    /**
     * Updates the editable display fields of this category. The technical code is never changed.
     *
     * @param name required replacement display name
     * @param description optional replacement description
     */
    public void updateProfile(String name, String description) {
        this.name = name;
        this.description = description;
    }

    /**
     * Deactivates this category so it can no longer be selected for a new Expense.
     *
     * @throws IllegalStateException if the category is already inactive
     */
    public void deactivate() {
        transition(true, false, "deactivate");
    }

    /**
     * Reactivates this category so it can be selected for a new Expense again.
     *
     * @throws IllegalStateException if the category is already active
     */
    public void reactivate() {
        transition(false, true, "reactivate");
    }

    /**
     * Applies one approved active-state transition.
     *
     * @param expectedActive the only permitted current active state
     * @param targetActive the approved active state after the operation
     * @param operationName human-readable operation name used in the rejection message
     * @throws IllegalStateException if the category is not in the permitted current state
     */
    private void transition(boolean expectedActive, boolean targetActive, String operationName) {
        if (active != expectedActive) {
            throw new IllegalStateException("Expense category cannot " + operationName + " from its current state");
        }
        active = targetActive;
    }

    /**
     * Returns the technical reference-data identifier.
     *
     * @return ExpenseCategory identifier
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the stable category code.
     *
     * @return approved Expense category code
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the optional category display name.
     *
     * @return category name, or {@code null} when none is configured
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the optional category description.
     *
     * @return category description, or {@code null} when none is configured
     */
    public String getDescription() {
        return description;
    }

    /**
     * Indicates whether this reference category is active.
     *
     * @return {@code true} when the category is active
     */
    public boolean isActive() {
        return active;
    }
}
