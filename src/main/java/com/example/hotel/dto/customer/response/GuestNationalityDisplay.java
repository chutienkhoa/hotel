package com.example.hotel.dto.customer.response;

/**
 * Provides the presentation-only country text and Unicode flag for a stored Guest nationality.
 *
 * <p>Country and flag resolution is delegated entirely to the shared {@link CountryCatalog} so
 * the Guest list flag display, the Create/Edit country dropdown, and the Guest list nationality
 * filter all resolve nationality text the same way.</p>
 */
public record GuestNationalityDisplay(String displayName, String flag) {

    /**
     * Creates a null-safe display representation without altering the stored nationality text.
     *
     * @param nationality stored optional nationality text
     * @return the original country text with a flag when it can safely be resolved
     */
    public static GuestNationalityDisplay from(String nationality) {
        if (nationality == null || nationality.isBlank()) {
            return new GuestNationalityDisplay("—", null);
        }

        String displayName = nationality.trim();
        return CountryCatalog.resolve(displayName)
                .map(country -> new GuestNationalityDisplay(displayName, country.flag()))
                .orElseGet(() -> new GuestNationalityDisplay(displayName, null));
    }
}
