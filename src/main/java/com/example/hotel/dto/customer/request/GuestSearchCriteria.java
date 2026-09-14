package com.example.hotel.dto.customer.request;

/**
 * Captures the independent, optional Guest Management list filters.
 */
public class GuestSearchCriteria {

    private String guestCode;
    private String firstName;
    private String lastName;
    private String email;
    private String nationality;

    /**
     * Returns the optional case-insensitive Guest Code filter fragment.
     *
     * @return the supplied filter fragment, or {@code null} when absent
     */
    public String getGuestCode() {
        return guestCode;
    }

    /**
     * Sets the Guest Code filter fragment supplied by the Guest list form.
     *
     * @param guestCode optional Guest Code filter fragment
     */
    public void setGuestCode(String guestCode) {
        this.guestCode = guestCode;
    }

    /**
     * Returns the optional case-insensitive first-name filter fragment.
     *
     * @return the supplied filter fragment, or {@code null} when absent
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Sets the first-name filter fragment supplied by the Guest list form.
     *
     * @param firstName optional first-name filter fragment
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * Returns the optional case-insensitive last-name filter fragment.
     *
     * @return the supplied filter fragment, or {@code null} when absent
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Sets the last-name filter fragment supplied by the Guest list form.
     *
     * @param lastName optional last-name filter fragment
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * Returns the optional case-insensitive email filter fragment.
     *
     * @return the supplied filter fragment, or {@code null} when absent
     */
    public String getEmail() {
        return email;
    }

    /**
     * Sets the email filter fragment supplied by the Guest list form.
     *
     * @param email optional email filter fragment
     */
    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * Returns the optional case-insensitive nationality filter fragment.
     *
     * @return the supplied filter fragment, or {@code null} when absent
     */
    public String getNationality() {
        return nationality;
    }

    /**
     * Sets the nationality filter fragment supplied by the Guest list form.
     *
     * @param nationality optional nationality filter fragment
     */
    public void setNationality(String nationality) {
        this.nationality = nationality;
    }

    /**
     * Trims every filter field and converts a blank value to an absent filter.
     */
    public void normalize() {
        guestCode = normalizeField(guestCode);
        firstName = normalizeField(firstName);
        lastName = normalizeField(lastName);
        email = normalizeField(email);
        nationality = normalizeField(nationality);
    }

    /**
     * Trims one filter value and converts blank input to an absent filter.
     *
     * @param value raw filter value supplied by the form
     * @return the trimmed value, or {@code null} when absent or blank
     */
    private static String normalizeField(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
