package com.example.hotel.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the Walk-in and OTA Room Selection filter row: only Room Type and Guests (two equal columns, no leftover column), no
 * separate "check availability" action, and the rooms still load from the stay dates and filter by room type on their own.
 */
class RoomSelectionToolbarTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates/check-in");
    private static final Path RESOURCES = Path.of("src/main/resources");

    /** Confirms the button, its icon and its message are gone from both pages, the script and the stylesheet. */
    @Test
    void checkAvailabilityActionIsRemovedEverywhere() throws IOException {
        for (String page : List.of("walk-in.html", "ota-entry.html")) {
            String html = Files.readString(TEMPLATES.resolve(page));
            assertFalse(html.contains("data-check-availability") || html.contains("walk-in-check")
                    || html.contains("checkin.walkIn.room.check}"), page);
            String toolbar = html.substring(html.indexOf("<div class=\"walk-in-room-toolbar\">"));
            toolbar = toolbar.substring(0, toolbar.indexOf("<p class=\"walk-in-room-range\""));
            assertFalse(toolbar.contains("<button") || toolbar.contains("<svg"), page + ": the row holds only the two fields");
            assertTrue(toolbar.contains("id=\"roomTypeFilter\"") && toolbar.contains("data-guests-context"), page);
        }
        String js = Files.readString(RESOURCES.resolve("static/js/booking/check-in-walk-in.js"));
        assertFalse(js.contains("checkButton") || js.contains("data-check-availability"));
        String css = Files.readString(RESOURCES.resolve("static/css/front-desk/walk-in.css"));
        assertFalse(css.contains("walk-in-check"));
        for (String bundle : List.of("messages.properties", "messages_vi.properties")) {
            assertFalse(Files.readString(RESOURCES.resolve(bundle)).contains("checkin.walkIn.room.check="), bundle);
        }
    }

    /** Confirms the filter row is two equal columns, with no third (button) column left behind. */
    @Test
    void filterRowIsTwoEqualColumns() throws IOException {
        String css = Files.readString(RESOURCES.resolve("static/css/front-desk/walk-in.css")).replaceAll("(?s)/\\*.*?\\*/", "");
        Matcher toolbar = Pattern.compile("\\.walk-in-room-toolbar\\s*\\{([^}]*)}").matcher(css);
        assertTrue(toolbar.find());
        assertTrue(toolbar.group(1).contains("grid-template-columns: repeat(2, minmax(0, 1fr))"), toolbar.group(1));
        assertFalse(toolbar.group(1).contains("auto;"), "no auto column for a button");
    }

    /** Confirms rooms still load from the stay dates and the room type still filters the list, both without a button. */
    @Test
    void roomsLoadAndFilterByThemselves() throws IOException {
        String js = Files.readString(RESOURCES.resolve("static/js/booking/check-in-walk-in.js"));
        assertTrue(js.contains("onApply: onStayChanged, onClear: onStayChanged"), "the stay dates drive the availability query");
        assertTrue(js.contains("const onStayChanged = () => {") && js.contains("onCheckOutChange();"));
        assertTrue(js.contains("typeFilter.addEventListener(\"change\", applyTypeFilter)"), "the type filters the list on change");
        assertTrue(js.contains("adultInput?.addEventListener(\"input\", updateGuestsContext)"), "the guest count refreshes by itself");
    }
}
