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
 * Guards that Walk-in and OTA Booking Not Entered take their stay dates from ONE shared range picker (the layout/stay-picker
 * fragment driving the shared stay-range-picker script) instead of two separate date inputs or their own copy of the markup.
 */
class CheckInStayPickerSharedTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path STATIC = Path.of("src/main/resources/static");

    /** Confirms both pages call the shared fragment once and carry no date inputs or picker markup of their own. */
    @Test
    void bothPagesUseTheSharedPickerFragment() throws IOException {
        for (String page : List.of("check-in/walk-in.html", "check-in/ota-entry.html")) {
            String html = Files.readString(TEMPLATES.resolve(page));
            assertEquals(1, html.split("layout/stay-picker :: stayPicker", -1).length - 1, page);
            assertFalse(html.contains("js-date-picker"), page + " must not keep separate date inputs");
            assertFalse(html.contains("data-stay-popover"), page + " must not carry its own picker markup");
            assertFalse(html.contains("data-stay-trigger"), page);
        }
        String fragment = Files.readString(TEMPLATES.resolve("layout/stay-picker.html"));
        assertEquals(1, fragment.split("<button aria-expanded", -1).length - 1, "one trigger control, not two date fields");
        assertTrue(fragment.contains("data-stay-popover") && fragment.contains("data-stay-calendar"));
        assertTrue(fragment.contains("name=\"checkOutDate\""), "the submitted check-out value is unchanged");
    }

    /** Confirms the page script drives the shared picker and no longer builds flatpickr date inputs for the stay. */
    @Test
    void pageScriptUsesTheSharedPicker() throws IOException {
        String js = Files.readString(STATIC.resolve("js/booking/check-in-walk-in.js"));
        assertTrue(js.contains("PmsStayRangePicker.init"));
        assertFalse(js.contains("_flatpickr"), "no flatpickr alternate-input handling for the stay dates");
        assertFalse(js.contains("restrictCheckOut"));

        String picker = Files.readString(STATIC.resolve("js/common/stay-range-picker.js"));
        assertTrue(picker.contains("data-fixed-from"), "the shared picker supports a locked check-in");
    }

    /**
     * Confirms the calendar is a positioned overlay owned by the shared picker: it is placed on open (aligned to the control's
     * right edge when asked, clamped into the main content area, flipped above when there is no room below, one month when two
     * do not fit), and both entry pages ask for right-edge alignment. The popover CSS stays an absolutely positioned layer.
     */
    @Test
    void calendarIsAClampedOverlayAlignedToTheRightEdge() throws IOException {
        String picker = Files.readString(STATIC.resolve("js/common/stay-range-picker.js"));
        assertTrue(picker.contains("data-popover-align") && picker.contains("function place()"));
        assertTrue(picker.contains("function boundsRect()") && picker.contains("root.closest(\"main\")"),
                "clamped to the main content area, never the sidebar");
        assertTrue(picker.contains("picker.set(\"showMonths\", 1)"), "one month when two do not fit");
        assertTrue(picker.contains("calc(100% + var(--dropdown-gap))"), "opens above when there is no room below");
        assertTrue(picker.contains("window.addEventListener(\"resize\", place)")
                && picker.contains("window.removeEventListener(\"resize\", place)"), "re-placed on resize, listener removed on close");

        String css = Files.readString(STATIC.resolve("css/common/stay-range-picker.css")).replaceAll("(?s)/\\*.*?\\*/", "");
        java.util.regex.Matcher popover = java.util.regex.Pattern.compile("\\.reservation-stay__popover\\s*\\{([^}]*)}").matcher(css);
        assertTrue(popover.find());
        assertTrue(popover.group(1).contains("position: absolute") && popover.group(1).contains("z-index: 30"), popover.group(1));

        for (String page : List.of("check-in/walk-in.html", "check-in/ota-entry.html")) {
            assertTrue(Files.readString(TEMPLATES.resolve(page)).contains("'end')}\"></div>"), page + " aligns the calendar to the right edge");
        }
        assertTrue(Files.readString(TEMPLATES.resolve("layout/stay-picker.html")).contains("data-popover-align=${popoverAlign}"));
    }
}
