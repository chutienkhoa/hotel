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
