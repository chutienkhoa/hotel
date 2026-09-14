package com.example.hotel.dto.customer.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Verifies null-safe, standard-library-backed Guest nationality presentation. */
class GuestNationalityDisplayTest {

    /** Confirms standard country names render with their corresponding Unicode flags. */
    @Test
    void shouldResolveKnownCountryNamesToUnicodeFlags() {
        assertEquals(new GuestNationalityDisplay("Vietnam", "🇻🇳"), GuestNationalityDisplay.from("Vietnam"));
        assertEquals(new GuestNationalityDisplay("Japan", "🇯🇵"), GuestNationalityDisplay.from("Japan"));
        assertEquals(
                new GuestNationalityDisplay("United States", "🇺🇸"),
                GuestNationalityDisplay.from("United States"));
    }

    /** Confirms common nationality demonyms resolve to the same flag as their country name. */
    @Test
    void shouldResolveKnownDemonymsToUnicodeFlags() {
        assertEquals(new GuestNationalityDisplay("Vietnamese", "🇻🇳"), GuestNationalityDisplay.from("Vietnamese"));
        assertEquals(new GuestNationalityDisplay("Japanese", "🇯🇵"), GuestNationalityDisplay.from("Japanese"));
        assertEquals(new GuestNationalityDisplay("American", "🇺🇸"), GuestNationalityDisplay.from("American"));
        assertEquals(new GuestNationalityDisplay("Korean", "🇰🇷"), GuestNationalityDisplay.from("Korean"));
        assertEquals(new GuestNationalityDisplay("Chinese", "🇨🇳"), GuestNationalityDisplay.from("Chinese"));
        assertEquals(new GuestNationalityDisplay("British", "🇬🇧"), GuestNationalityDisplay.from("British"));
        assertEquals(new GuestNationalityDisplay("French", "🇫🇷"), GuestNationalityDisplay.from("French"));
        assertEquals(new GuestNationalityDisplay("German", "🇩🇪"), GuestNationalityDisplay.from("German"));
    }

    /** Confirms demonym resolution is case-insensitive while preserving the original stored casing. */
    @Test
    void shouldResolveDemonymsCaseInsensitivelyWhilePreservingStoredText() {
        GuestNationalityDisplay display = GuestNationalityDisplay.from("  vietnamese  ");

        assertEquals("vietnamese", display.displayName());
        assertEquals("🇻🇳", display.flag());
    }

    /** Confirms blank nationalities remain an explicit non-country presentation. */
    @Test
    void shouldRenderBlankNationalityAsDash() {
        assertEquals("—", GuestNationalityDisplay.from(null).displayName());
        assertNull(GuestNationalityDisplay.from(null).flag());
        assertEquals("—", GuestNationalityDisplay.from("  ").displayName());
        assertNull(GuestNationalityDisplay.from("  ").flag());
    }

    /** Confirms unknown historical text remains visible without fabricating a country flag. */
    @Test
    void shouldKeepUnknownNationalityWithoutFlag() {
        GuestNationalityDisplay display = GuestNationalityDisplay.from("Martian residency");

        assertEquals("Martian residency", display.displayName());
        assertNull(display.flag());
    }
}
