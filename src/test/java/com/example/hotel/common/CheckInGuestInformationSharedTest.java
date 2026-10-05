package com.example.hotel.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards that the Walk-in and OTA Booking Not Entered pages present the selected guest through ONE shared fragment (the
 * compact information table), and that the old input-like box grid is gone.
 */
class CheckInGuestInformationSharedTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates/check-in");
    private static final Path CSS = Path.of("src/main/resources/static/css");

    /** Confirms both pages reuse the fragment and neither carries its own copy of the details list. */
    @Test
    void bothPagesUseTheSharedFragment() throws IOException {
        for (String page : List.of("walk-in.html", "ota-entry.html")) {
            String html = Files.readString(TEMPLATES.resolve(page));
            assertTrue(html.contains("~{check-in/guest-fragments :: selectedGuest}"), page);
            assertFalse(html.contains("data-guest-full-name"), page + " must not carry its own copy");
        }
        String fragment = Files.readString(TEMPLATES.resolve("guest-fragments.html"));
        assertEquals(1, fragment.split("data-guest-information", -1).length - 1);
        assertTrue(fragment.contains("label-value-rows--info"));
        // The passport preview reuses the summary screens' composition and sits before (left of) the table.
        int preview = fragment.indexOf("class=\"walk-in-doc\"");
        assertTrue(fragment.contains("walk-in-summary-body") && preview > 0 && preview < fragment.indexOf("<dl"));
        assertTrue(fragment.contains("walk-in-doc walk-in-doc--empty"));
        assertTrue(fragment.contains("#{checkin.review.documents.none}"), "the PMS empty-state message");
        // Row order: Guest Code first, then name, nationality, phone, email, date of birth, ID / passport number.
        int previous = fragment.indexOf("<dl");
        for (String hook : List.of("data-guest-code", "data-guest-full-name", "data-guest-nationality", "data-guest-phone",
                "data-guest-email", "data-guest-dob", "data-guest-id-document")) {
            int at = fragment.indexOf(hook, previous);
            assertTrue(at > previous, hook + " is in the specified order");
            previous = at;
        }
    }

    /** Confirms the old bordered, muted "input" cells and two-column grid no longer exist for the guest details. */
    @Test
    void oldInputLikeGridIsGone() throws IOException {
        String walkIn = Files.readString(CSS.resolve("front-desk/walk-in.css")).replaceAll("(?s)/\\*.*?\\*/", "");
        assertFalse(walkIn.contains(".walk-in-guest-details dd"), "no input-like value boxes");
        assertFalse(walkIn.contains(".walk-in-guest-details dt"));
        assertFalse(walkIn.contains(".walk-in-guest-details div"));
        assertFalse(walkIn.matches("(?s).*\\.walk-in-guest-details\\s*\\{[^}]*grid-template-columns.*"));

        String layout = Files.readString(CSS.resolve("common/layout.css")).replaceAll("(?s)/\\*.*?\\*/", "");
        assertTrue(layout.contains(".label-value-rows--info {"));
    }
}
