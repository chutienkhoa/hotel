package com.example.hotel.dto.customer.response;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Provides the presentation-only country text and Unicode flag for a stored Guest nationality.
 */
public record GuestNationalityDisplay(String displayName, String flag) {

    private static final Map<String, String> COUNTRY_CODES_BY_NAME = createCountryCodesByName();

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
        return countryCodeFor(displayName)
                .map(countryCode -> new GuestNationalityDisplay(displayName, flagFor(countryCode)))
                .orElseGet(() -> new GuestNationalityDisplay(displayName, null));
    }

    /**
     * Resolves a stored name or ISO alpha-2 code through Java's standard country metadata.
     *
     * @param nationality trimmed stored nationality text
     * @return the matching ISO alpha-2 country code when available
     */
    private static Optional<String> countryCodeFor(String nationality) {
        String normalizedNationality = nationality.toLowerCase(Locale.ROOT);
        if (nationality.length() == 2) {
            String countryCode = nationality.toUpperCase(Locale.ROOT);
            if (COUNTRY_CODES_BY_NAME.containsValue(countryCode)) {
                return Optional.of(countryCode);
            }
        }
        return Optional.ofNullable(COUNTRY_CODES_BY_NAME.get(normalizedNationality));
    }

    /**
     * Converts an ISO alpha-2 country code into its two regional-indicator Unicode code points.
     *
     * @param countryCode ISO alpha-2 country code
     * @return the corresponding Unicode flag emoji
     */
    private static String flagFor(String countryCode) {
        return new StringBuilder()
                .appendCodePoint(0x1F1E6 + countryCode.charAt(0) - 'A')
                .appendCodePoint(0x1F1E6 + countryCode.charAt(1) - 'A')
                .toString();
    }

    /**
     * Builds a normalized lookup from Java's complete ISO country catalogue.
     *
     * @return country display names mapped to ISO alpha-2 codes
     */
    private static Map<String, String> createCountryCodesByName() {
        return Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2).stream()
                .collect(Collectors.toUnmodifiableMap(
                        countryCode -> new Locale("", countryCode)
                                .getDisplayCountry(Locale.ENGLISH)
                                .toLowerCase(Locale.ROOT),
                        countryCode -> countryCode));
    }
}
