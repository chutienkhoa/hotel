package com.example.hotel.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Guards that every card header on the Walk-in and OTA entry forms is the same row height (the height of a header action
 * button), so a header with actions (Guest) and one without (Stay) put their icon and title the same distance below the
 * card's top border, without per-card margins.
 */
class WalkInCardHeaderAlignmentTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/front-desk/walk-in.css");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates/check-in");

    /** Confirms one shared header row height drives both the header and its action buttons. */
    @Test
    void headerRowHeightIsOneSharedValue() throws IOException {
        String css = Files.readString(CSS).replaceAll("(?s)/\\*.*?\\*/", "");

        assertTrue(Pattern.compile("\\.walk-in-card\\s*\\{[^}]*--walk-in-header-height:\\s*2\\.25rem").matcher(css).find());
        Matcher header = Pattern.compile(
                "\\.walk-in-grid:not\\(\\.walk-in-grid--summary\\) \\.walk-in-card__header\\s*\\{([^}]*)}").matcher(css);
        assertTrue(header.find());
        assertTrue(header.group(1).contains("min-height: var(--walk-in-header-height)"), header.group(1));
        Matcher buttons = Pattern.compile("\\.walk-in-card__actions \\.button\\s*\\{([^}]*)}").matcher(css);
        assertTrue(buttons.find());
        assertTrue(buttons.group(1).contains("height: var(--walk-in-header-height)"), buttons.group(1));
    }

    /** Confirms no card header is nudged with non-zero margins or padding (the alignment comes from the shared row height). */
    @Test
    void noHeaderUsesAdHocSpacing() throws IOException {
        String css = Files.readString(CSS).replaceAll("(?s)/\\*.*?\\*/", "");
        Matcher rules = Pattern.compile("([^{}]+)\\{([^{}]*)}").matcher(css);
        while (rules.find()) {
            String selector = rules.group(1);
            if (selector.contains("walk-in-card__header") && !selector.contains("__header--action")) {
                assertFalse(Pattern.compile("(^|[;\\s])(margin|padding)[a-z-]*\\s*:\\s*+(?!0\\s*(;|$))").matcher(rules.group(2)).find(),
                        selector + " must not nudge with a non-zero margin or padding (a margin: 0 reset is fine)");
            }
        }
    }

    /** Confirms every card of both forms uses the same header markup, so the shared rule reaches all of them. */
    @Test
    void everyFormCardUsesTheSharedHeader() throws IOException {
        for (String page : List.of("walk-in.html", "ota-entry.html")) {
            String html = Files.readString(TEMPLATES.resolve(page));
            int cards = html.split("class=\"card walk-in-card ", -1).length - 1;
            int headers = html.split("<header class=\"walk-in-card__header", -1).length - 1;
            assertEquals(cards, headers, page + ": one shared header per card");
            assertTrue(cards >= 4, page);
            assertFalse(html.contains("walk-in-card__header\" style"), page);
        }
    }

    /**
     * Confirms Stay Details ends level with the Guest card by a slightly shorter Notes textarea (below the shared 7rem minimum)
     * rather than by a fixed card height, a transform or a negative margin, and that both entry pages share that one rule.
     */
    @Test
    void stayDetailsFinishesLevelWithTheGuestCardThroughTheNotesTextarea() throws IOException {
        String css = Files.readString(CSS).replaceAll("(?s)/\\*.*?\\*/", "");
        Matcher textarea = Pattern.compile("\\.walk-in-card textarea\\s*\\{([^}]*)}").matcher(css);
        assertTrue(textarea.find());
        assertTrue(textarea.group(1).contains("min-height: 6.2rem"), textarea.group(1));

        String layout = Files.readString(Path.of("src/main/resources/static/css/common/layout.css")).replaceAll("(?s)/\\*.*?\\*/", "");
        assertTrue(layout.contains("min-height: 7rem"), "the shared textarea minimum is unchanged for every other page");

        Matcher rules = Pattern.compile("([^{}]+)\\{([^{}]*)}").matcher(css);
        while (rules.find()) {
            String selector = rules.group(1);
            if (selector.contains("walk-in-card--stay") || selector.contains("walk-in-card--guest")
                    || selector.contains("walk-in-fields")) {
                assertFalse(Pattern.compile("(^|[;\\s])(height|transform)\\s*:|margin[a-z-]*:\\s*-").matcher(rules.group(2)).find(),
                        selector + " must not fix a height or use a transform / negative margin");
            }
        }
        for (String page : List.of("walk-in.html", "ota-entry.html")) {
            assertTrue(Files.readString(TEMPLATES.resolve(page)).contains("<textarea id=\"notes\""), page);
        }
    }

    /**
     * Confirms the Stay range picker's value is inset like the other field values (the Nights box uses the same --space-3), by
     * the standard token on the trigger's LEFT padding only, so the right padding and the calendar icon are untouched.
     */
    @Test
    void stayRangeValueUsesTheStandardFieldInset() throws IOException {
        String css = Files.readString(CSS).replaceAll("(?s)/\\*.*?\\*/", "");
        Matcher trigger = Pattern.compile("\\.walk-in-card \\.reservation-stay__trigger\\s*\\{([^}]*)}").matcher(css);
        assertTrue(trigger.find());
        assertTrue(trigger.group(1).contains("padding-left: var(--space-3)"), trigger.group(1));
        assertFalse(trigger.group(1).contains("padding-right") || trigger.group(1).contains("height")
                || trigger.group(1).contains("width") || trigger.group(1).contains("font-size"), trigger.group(1));

        Matcher readonly = Pattern.compile("\\.walk-in-readonly\\s*\\{([^}]*)}").matcher(css);
        assertTrue(readonly.find());
        assertTrue(readonly.group(1).contains("padding: 0 var(--space-3)"), "the Nights box is the reference inset");
    }
}
