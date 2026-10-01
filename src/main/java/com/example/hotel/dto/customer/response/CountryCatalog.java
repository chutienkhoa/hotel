package com.example.hotel.dto.customer.response;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Provides the single shared, presentation-agnostic ISO country reference used by every Guest
 * nationality UI surface: the Create/Edit country dropdown, the Guest List nationality filter
 * dropdown, and the Guest List nationality flag display.
 *
 * <p>The complete country list is derived from Java's standard ISO country metadata rather than
 * hard-coded. A small, explicitly curated set of common nationality demonyms (e.g. "Japanese")
 * is also recognized so legacy free-text Guest data continues to resolve to a flag and, for the
 * Edit form, to its canonical country.</p>
 */
public final class CountryCatalog {

    private static final Map<String, String> LEGACY_DEMONYM_TO_CANONICAL_NAME = Map.ofEntries(
            Map.entry("Vietnamese", "Vietnam"),
            Map.entry("Japanese", "Japan"),
            Map.entry("American", "United States"),
            Map.entry("Korean", "South Korea"),
            Map.entry("Chinese", "China"),
            Map.entry("British", "United Kingdom"),
            Map.entry("French", "France"),
            Map.entry("German", "Germany"));

    private static final List<Country> COUNTRIES = buildCountries();
    private static final Map<String, String> CODE_BY_NAME_LOWER = buildCodeByNameLower();
    private static final Map<String, String> CANONICAL_NAME_BY_LEGACY_LOWER = buildCanonicalNameByLegacyLower();

    private CountryCatalog() {}

    /**
     * Returns every ISO country available for Guest nationality selection, sorted by canonical
     * English country name.
     *
     * @return the immutable, name-sorted country list
     */
    public static List<Country> countries() {
        return COUNTRIES;
    }

    /**
     * Resolves stored or submitted nationality text to its {@link Country}, trying an ISO
     * alpha-2 code, then a canonical country name, then a known legacy demonym, in that order.
     *
     * @param rawText stored or submitted nationality text
     * @return the matching country when the text can be safely resolved
     */
    public static Optional<Country> resolve(String rawText) {
        if (rawText == null) {
            return Optional.empty();
        }
        String trimmed = rawText.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);

        if (trimmed.length() == 2) {
            String code = trimmed.toUpperCase(Locale.ROOT);
            Optional<Country> byCode = countries().stream()
                    .filter(country -> country.code().equals(code))
                    .findFirst();
            if (byCode.isPresent()) {
                return byCode;
            }
        }

        String codeByName = CODE_BY_NAME_LOWER.get(lower);
        if (codeByName != null) {
            return countryByCode(codeByName);
        }

        String canonicalNameByDemonym = CANONICAL_NAME_BY_LEGACY_LOWER.get(lower);
        if (canonicalNameByDemonym != null) {
            String code = CODE_BY_NAME_LOWER.get(canonicalNameByDemonym.toLowerCase(Locale.ROOT));
            return countryByCode(code);
        }

        return Optional.empty();
    }

    /**
     * Resolves stored or submitted nationality text to its canonical country name, safely
     * mapping a known legacy demonym without altering the underlying stored value.
     *
     * @param rawText stored or submitted nationality text
     * @return the canonical country name when the text can be safely resolved
     */
    public static Optional<String> canonicalNameFor(String rawText) {
        return resolve(rawText).map(Country::name);
    }

    /**
     * Determines whether submitted text exactly matches one canonical country name accepted by
     * the Create and Edit Guest forms.
     *
     * @param rawText submitted nationality text
     * @return {@code true} when the value is a canonical country name
     */
    public static boolean isCanonicalCountryName(String rawText) {
        if (rawText == null) {
            return false;
        }
        String trimmed = rawText.trim();
        return !trimmed.isEmpty() && CODE_BY_NAME_LOWER.containsKey(trimmed.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns every stored-value variant that should be treated as belonging to the given
     * canonical country: the canonical name itself, plus any known legacy demonym that maps to
     * it. Used to match historical free-text Guest data when filtering by a selected country.
     *
     * @param canonicalName canonical country name selected in the Guest list filter
     * @return the canonical name and its known legacy synonyms; never empty
     */
    public static List<String> acceptableStoredValues(String canonicalName) {
        List<String> values = new ArrayList<>();
        values.add(canonicalName);
        LEGACY_DEMONYM_TO_CANONICAL_NAME.forEach((demonym, canonical) -> {
            if (canonical.equals(canonicalName)) {
                values.add(demonym);
            }
        });
        return List.copyOf(values);
    }

    /**
     * Looks up one country by its ISO alpha-2 code.
     *
     * @param code ISO alpha-2 country code
     * @return the matching country when the code is known
     */
    private static Optional<Country> countryByCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return countries().stream().filter(country -> country.code().equals(code)).findFirst();
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
     * Builds the complete, name-sorted country list from Java's ISO country catalogue.
     *
     * @return the immutable, name-sorted country list
     */
    private static List<Country> buildCountries() {
        return Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2).stream()
                .map(code -> new Country(
                        code, Locale.of("", code).getDisplayCountry(Locale.ENGLISH), flagFor(code)))
                .sorted(Comparator.comparing(Country::name))
                .toList();
    }

    /**
     * Builds a normalized lookup from lower-cased canonical country name to ISO alpha-2 code.
     *
     * @return canonical country names mapped to ISO alpha-2 codes
     */
    private static Map<String, String> buildCodeByNameLower() {
        return COUNTRIES.stream()
                .collect(Collectors.toUnmodifiableMap(
                        country -> country.name().toLowerCase(Locale.ROOT), Country::code));
    }

    /**
     * Builds a normalized lookup from lower-cased legacy demonym to canonical country name.
     *
     * @return legacy demonyms mapped to canonical country names
     */
    private static Map<String, String> buildCanonicalNameByLegacyLower() {
        return LEGACY_DEMONYM_TO_CANONICAL_NAME.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue));
    }
}
