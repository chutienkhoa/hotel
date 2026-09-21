package com.example.hotel.dto.customer.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the shared ISO country reference used by every Guest nationality UI surface. */
class CountryCatalogTest {

    /** Confirms representative countries resolve to their correct ISO code, name, and flag. */
    @Test
    void shouldExposeRepresentativeCountriesWithCorrectCodeNameAndFlag() {
        assertEquals(new Country("VN", "Vietnam", "🇻🇳"), findByName("Vietnam"));
        assertEquals(new Country("JP", "Japan", "🇯🇵"), findByName("Japan"));
        assertEquals(new Country("US", "United States", "🇺🇸"), findByName("United States"));
        assertEquals(new Country("GB", "United Kingdom", "🇬🇧"), findByName("United Kingdom"));
        assertEquals(new Country("KR", "South Korea", "🇰🇷"), findByName("South Korea"));
    }

    /** Confirms the country list is sorted by canonical English country name. */
    @Test
    void shouldSortCountriesByCanonicalName() {
        List<Country> countries = CountryCatalog.countries();

        List<String> actualNames = countries.stream().map(Country::name).toList();
        List<String> sortedNames = actualNames.stream().sorted().toList();
        assertEquals(sortedNames, actualNames);
    }

    /** Confirms {@link CountryCatalog#resolve} accepts a canonical country name. */
    @Test
    void shouldResolveCanonicalCountryName() {
        assertEquals("VN", CountryCatalog.resolve("Vietnam").orElseThrow().code());
    }

    /** Confirms {@link CountryCatalog#resolve} accepts an ISO alpha-2 code. */
    @Test
    void shouldResolveIsoAlpha2Code() {
        assertEquals("Japan", CountryCatalog.resolve("JP").orElseThrow().name());
    }

    /** Confirms {@link CountryCatalog#resolve} accepts a known legacy nationality demonym. */
    @Test
    void shouldResolveKnownLegacyDemonym() {
        assertEquals("Japan", CountryCatalog.resolve("Japanese").orElseThrow().name());
        assertEquals("South Korea", CountryCatalog.resolve("Korean").orElseThrow().name());
        assertEquals("United States", CountryCatalog.resolve("American").orElseThrow().name());
    }

    /** Confirms an unresolvable value returns no match rather than guessing a country. */
    @Test
    void shouldNotResolveUnknownNationalityText() {
        assertTrue(CountryCatalog.resolve("Atlantean").isEmpty());
    }

    /** Confirms selecting a canonical country returns itself plus its known legacy demonyms. */
    @Test
    void shouldReturnCanonicalNameAndKnownLegacySynonymsAsAcceptableStoredValues() {
        assertEquals(List.of("Japan", "Japanese"), CountryCatalog.acceptableStoredValues("Japan"));
        assertEquals(List.of("Vietnam", "Vietnamese"), CountryCatalog.acceptableStoredValues("Vietnam"));
        assertEquals(
                List.of("United States", "American"), CountryCatalog.acceptableStoredValues("United States"));
        assertEquals(
                List.of("South Korea", "Korean"), CountryCatalog.acceptableStoredValues("South Korea"));
    }

    /** Confirms a country with no known legacy demonym returns only its canonical name. */
    @Test
    void shouldReturnOnlyCanonicalNameWhenNoLegacyDemonymIsKnown() {
        assertEquals(List.of("Singapore"), CountryCatalog.acceptableStoredValues("Singapore"));
    }

    /**
     * Finds a country in the shared catalogue by its canonical name.
     *
     * @param name canonical country name to find
     * @return the matching country
     */
    private Country findByName(String name) {
        return CountryCatalog.countries().stream()
                .filter(country -> country.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
