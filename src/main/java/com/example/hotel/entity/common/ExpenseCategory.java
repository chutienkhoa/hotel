package com.example.hotel.entity.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Represents read-only Expense v1 reference data used to classify Expenses. */
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
