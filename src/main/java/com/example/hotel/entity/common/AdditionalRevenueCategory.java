package com.example.hotel.entity.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Represents database-backed reference data used to classify Additional Revenue records. */
@Entity
@Table(name = "additional_revenue_category")
public class AdditionalRevenueCategory extends AuditedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    private String name;

    private String description;

    @Column(nullable = false)
    private boolean active;

    /** Creates an empty instance for JPA. */
    protected AdditionalRevenueCategory() {}

    /** Creates a new active Additional Revenue category with an immutable technical code. */
    public static AdditionalRevenueCategory create(String code, String name, String description) {
        AdditionalRevenueCategory category = new AdditionalRevenueCategory();
        category.id = UUID.randomUUID();
        category.code = code;
        category.active = true;
        category.updateProfile(name, description);
        return category;
    }

    /** Updates the editable display fields. The technical code remains immutable. */
    public void updateProfile(String name, String description) {
        this.name = name;
        this.description = description;
    }

    /** Deactivates this category so it cannot be selected for new Additional Revenue. */
    public void deactivate() {
        transition(true, false, "deactivate");
    }

    /** Reactivates this category so it can be selected for new Additional Revenue again. */
    public void reactivate() {
        transition(false, true, "reactivate");
    }

    private void transition(boolean expectedActive, boolean targetActive, String operationName) {
        if (active != expectedActive) {
            throw new IllegalStateException(
                    "Additional Revenue category cannot " + operationName + " from its current state");
        }
        active = targetActive;
    }

    public UUID getId() { return id; }

    public String getCode() { return code; }

    public String getName() { return name; }

    public String getDescription() { return description; }

    public boolean isActive() { return active; }
}
