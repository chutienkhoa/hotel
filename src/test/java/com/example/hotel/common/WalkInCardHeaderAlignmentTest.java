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
}
