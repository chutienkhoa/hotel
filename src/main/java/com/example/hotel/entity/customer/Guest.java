package com.example.hotel.entity.customer;

import com.example.hotel.entity.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/** Represents a hotel guest and the profile data used by reservations. */
@Entity
@Table(name = "guest")
public class Guest extends AuditedEntity {
    @Id
    private UUID id;

    @Column(name = "guest_code", nullable = false, unique = true)
    private String guestCode;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    private String email;

    private String phone;

    private String nationality;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "id_document_number", length = 50)
    private String idDocumentNumber;

    private String address;

    /** Tạo thực thể rỗng cho JPA. */
    protected Guest() {}

    /**
     * Creates a guest with a backend-generated immutable guest code.
     *
     * @param id guest identifier
     * @param guestCode generated unique guest code
     * @param firstName guest first name
     * @param lastName guest last name
     * @param email guest email address
     * @param phone guest phone number
     * @param nationality guest nationality
     * @param dateOfBirth guest date of birth
     * @param address guest address
     * @return the new guest entity
     */
    public static Guest create(
            UUID id,
            String guestCode,
            String firstName,
            String lastName,
            String email,
            String phone,
            String nationality,
            LocalDate dateOfBirth,
            String address) {
        return create(id, guestCode, firstName, lastName, email, phone, nationality, dateOfBirth, null, address);
    }

    /**
     * Creates a guest, including the optional structured ID / Passport Number, with a backend-generated immutable
     * guest code.
     *
     * @param id guest identifier
     * @param guestCode generated unique guest code
     * @param firstName guest first name
     * @param lastName guest last name
     * @param email guest email address
     * @param phone guest phone number
     * @param nationality guest nationality
     * @param dateOfBirth guest date of birth
     * @param idDocumentNumber guest national ID / passport number
     * @param address guest address
     * @return the new guest entity
     */
    public static Guest create(
            UUID id,
            String guestCode,
            String firstName,
            String lastName,
            String email,
            String phone,
            String nationality,
            LocalDate dateOfBirth,
            String idDocumentNumber,
            String address) {
        Guest guest = new Guest();
        guest.id = id;
        guest.guestCode = guestCode;
        guest.updateProfile(
                firstName, lastName, email, phone, nationality, dateOfBirth, idDocumentNumber, address);
        return guest;
    }

    /**
     * Updates mutable guest profile data without changing the generated guest code.
     *
     * @param firstName guest first name
     * @param lastName guest last name
     * @param email guest email address
     * @param phone guest phone number
     * @param nationality guest nationality
     * @param dateOfBirth guest date of birth
     * @param address guest address
     */
    public void updateProfile(
            String firstName,
            String lastName,
            String email,
            String phone,
            String nationality,
            LocalDate dateOfBirth,
            String address) {
        updateProfile(firstName, lastName, email, phone, nationality, dateOfBirth, this.idDocumentNumber, address);
    }

    /**
     * Updates mutable guest profile data, including the optional structured ID / Passport Number, without changing
     * the generated guest code.
     *
     * @param firstName guest first name
     * @param lastName guest last name
     * @param email guest email address
     * @param phone guest phone number
     * @param nationality guest nationality
     * @param dateOfBirth guest date of birth
     * @param idDocumentNumber guest national ID / passport number
     * @param address guest address
     */
    public void updateProfile(
            String firstName,
            String lastName,
            String email,
            String phone,
            String nationality,
            LocalDate dateOfBirth,
            String idDocumentNumber,
            String address) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phone = phone;
        this.nationality = nationality;
        this.dateOfBirth = dateOfBirth;
        this.idDocumentNumber = idDocumentNumber;
        this.address = address;
    }

    /**
     * Trả về định danh của khách.
     *
     * @return định danh khách
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the unique guest code used to identify the guest in reservation forms.
     *
     * @return the guest code
     */
    public String getGuestCode() {
        return guestCode;
    }

    /**
     * Returns the guest first name.
     *
     * @return the first name, or {@code null} when it has not been supplied
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Returns the guest last name.
     *
     * @return the last name, or {@code null} when it has not been supplied
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Returns the guest email address.
     *
     * @return the email address, or {@code null} when it has not been supplied
     */
    public String getEmail() {
        return email;
    }

    /**
     * Returns the guest phone number.
     *
     * @return the phone number, or {@code null} when it has not been supplied
     */
    public String getPhone() {
        return phone;
    }

    /**
     * Returns the guest nationality.
     *
     * @return the nationality, or {@code null} when it has not been supplied
     */
    public String getNationality() {
        return nationality;
    }

    /**
     * Returns the guest date of birth.
     *
     * @return the date of birth, or {@code null} when it has not been supplied
     */
    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    /**
     * Returns the guest's national ID / passport number.
     *
     * @return the ID document number, or {@code null} when it has not been supplied
     */
    public String getIdDocumentNumber() {
        return idDocumentNumber;
    }

    /**
     * Returns the guest address.
     *
     * @return the address, or {@code null} when it has not been supplied
     */
    public String getAddress() {
        return address;
    }
}
