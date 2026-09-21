package com.example.hotel.entity.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Represents a hotel employee/person managed by Staff Management. A Staff member is distinct
 * from {@link AppUser}: Staff may exist without ever holding a PMS login account, and a Staff
 * member is linked to at most one account through the nullable, unique {@code app_user_id}.
 */
@Entity
@Table(name = "staff")
public class Staff extends AuditedEntity {

    @Id
    private UUID id;

    @Column(name = "staff_code", nullable = false, unique = true)
    private String staffCode;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    private String phone;

    private String email;

    private String position;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private boolean active;

    private String notes;

    @Column(name = "app_user_id", unique = true)
    private UUID appUserId;

    /** Creates an empty Staff instance for JPA. */
    protected Staff() {}

    /**
     * Creates a new active Staff member with an immutable, backend-generated Staff Code.
     *
     * @param id Staff identifier
     * @param staffCode generated unique, immutable Staff Code
     * @param firstName required first name
     * @param lastName required last name
     * @param phone optional phone number
     * @param email optional email address
     * @param position optional free-text position
     * @param startDate required employment start date
     * @param notes optional notes
     * @return the new active Staff member
     */
    public static Staff create(
            UUID id,
            String staffCode,
            String firstName,
            String lastName,
            String phone,
            String email,
            String position,
            LocalDate startDate,
            String notes) {
        Staff staff = new Staff();
        staff.id = id;
        staff.staffCode = staffCode;
        staff.active = true;
        staff.updateProfile(firstName, lastName, phone, email, position, startDate, notes);
        return staff;
    }

    /**
     * Updates the editable profile fields of this Staff member. The Staff Code and active state
     * are never changed by this operation.
     *
     * @param firstName required first name
     * @param lastName required last name
     * @param phone optional phone number
     * @param email optional email address
     * @param position optional free-text position
     * @param startDate required employment start date
     * @param notes optional notes
     */
    public void updateProfile(
            String firstName,
            String lastName,
            String phone,
            String email,
            String position,
            LocalDate startDate,
            String notes) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
        this.email = email;
        this.position = position;
        this.startDate = startDate;
        this.notes = notes;
    }

    /**
     * Deactivates this Staff member so new Daily Work Record entries can no longer be created
     * for them. Historical Daily Work Record rows are never affected.
     *
     * @throws IllegalStateException if the Staff member is already inactive
     */
    public void deactivate() {
        transition(true, false, "deactivate");
    }

    /**
     * Reactivates this Staff member so they become eligible for Daily Work Record entry again.
     *
     * @throws IllegalStateException if the Staff member is already active
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
     * @throws IllegalStateException if the Staff member is not in the permitted current state
     */
    private void transition(boolean expectedActive, boolean targetActive, String operationName) {
        if (active != expectedActive) {
            throw new IllegalStateException("Staff cannot " + operationName + " from its current state");
        }
        active = targetActive;
    }

    /**
     * Returns the Staff identifier.
     *
     * @return Staff identifier
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the immutable, backend-generated Staff Code.
     *
     * @return Staff Code
     */
    public String getStaffCode() {
        return staffCode;
    }

    /**
     * Returns the Staff member's first name.
     *
     * @return first name
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Returns the Staff member's last name.
     *
     * @return last name
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Returns the Staff member's phone number.
     *
     * @return phone number, or {@code null} when not supplied
     */
    public String getPhone() {
        return phone;
    }

    /**
     * Returns the Staff member's email address.
     *
     * @return email address, or {@code null} when not supplied
     */
    public String getEmail() {
        return email;
    }

    /**
     * Returns the Staff member's free-text position.
     *
     * @return position, or {@code null} when not supplied
     */
    public String getPosition() {
        return position;
    }

    /**
     * Returns the Staff member's employment start date.
     *
     * @return start date
     */
    public LocalDate getStartDate() {
        return startDate;
    }

    /**
     * Indicates whether this Staff member is currently active.
     *
     * @return {@code true} when active
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Returns the optional Staff notes.
     *
     * @return notes, or {@code null} when not supplied
     */
    public String getNotes() {
        return notes;
    }

    /**
     * Returns the identifier of the PMS account linked to this Staff member, if any.
     *
     * @return the linked user account identifier, or {@code null} when no account is linked
     */
    public UUID getAppUserId() {
        return appUserId;
    }

    /**
     * Links this Staff member to a PMS user account. Uniqueness is guarded by the database.
     *
     * @param appUserId identifier of the user account to link
     */
    public void linkAppUser(UUID appUserId) {
        this.appUserId = appUserId;
    }

    /** Removes any link between this Staff member and a PMS user account. */
    public void unlinkAppUser() {
        this.appUserId = null;
    }
}
