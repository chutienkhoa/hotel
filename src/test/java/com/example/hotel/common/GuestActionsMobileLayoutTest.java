package com.example.hotel.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the narrow-screen layout of the Guest card header actions on the Walk-in and OTA forms (one shared stylesheet): the two
 * buttons stay compact on one right-aligned row under the title instead of stretching to full width, and on narrow phones only
 * their padding and gaps tighten (never the font size).
 */
class GuestActionsMobileLayoutTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/front-desk/walk-in.css");

    /** Confirms the base container is a right-aligned, gapped, wrapping row of content-width buttons. */
    @Test
    void actionsAreARightAlignedRowOfCompactButtons() throws IOException {
        String css = css();
        String actions = rule(css, "\\.walk-in-card__actions");
        assertTrue(actions.contains("display: flex") && actions.contains("justify-content: flex-end")
                && actions.contains("flex-wrap: wrap") && actions.contains("gap: var(--space-2)"), actions);
    }

    /** Confirms at 40rem and below the actions take their own full-width row, right-aligned, with no full-width buttons. */
    @Test
    void phoneLayoutKeepsContentWidthButtonsUnderTheTitle() throws IOException {
        String block = media(css(), "@media (max-width: 40rem)");
        String actions = rule(block, "\\.walk-in-card__actions");
        assertTrue(actions.contains("grid-column: 1 / -1"), actions);
        assertFalse(actions.contains("justify-content: stretch"), "the row is right-aligned, not stretched");
        assertFalse(Pattern.compile("\\.walk-in-card__actions \\.button\\s*\\{[^}]*(width: 100%|flex: 1 1 100%)").matcher(block).find(),
                "no full-width buttons");
    }

    /** Confirms narrow phones only tighten padding and gaps, and never shrink the font. */
    @Test
    void narrowPhonesTightenSpacingButNotTheFont() throws IOException {
        String block = media(css(), "@media (max-width: 26rem)");
        String buttons = rule(block, "\\.walk-in-card__actions \\.button");
        assertTrue(buttons.contains("padding: 0 var(--space-2)") && buttons.contains("gap: 0.25rem"), buttons);
        assertFalse(block.contains("font-size"), "the font size is never reduced");
        assertTrue(rule(block, "\\.walk-in-card__actions").contains("gap: 0.375rem"));
    }

    /**
     * Confirms the title row and the button row are separated by the standard 12px gap on phones only: a row gap on the header
     * of the entry forms inside the phone block, with no margin or absolute-positioning workaround, and nothing outside it.
     */
    @Test
    void titleRowAndButtonRowAreSeparatedByTheStandardGapOnPhonesOnly() throws IOException {
        String css = css();
        String phone = media(css, "@media (max-width: 40rem)");
        String header = rule(phone, "\\.walk-in-grid:not\\(\\.walk-in-grid--summary\\) \\.walk-in-card__header--action");
        assertTrue(header.contains("row-gap: var(--space-3)"), header);
        assertFalse(header.contains("margin") || header.contains("position") || header.contains("transform"), header);

        String outsidePhone = css.replace(phone, "");
        assertFalse(Pattern.compile("walk-in-card__header--action[^{]*\\{[^}]*(row-gap|margin)").matcher(outsidePhone).find(),
                "desktop and tablet headers are unchanged");
    }

    /**
     * Confirms the Guest card's passport image / empty-state box is centred on phones only, by giving it its (16rem-capped) width and
     * auto side margins in the phone block, with no size, vertical or positioning change, and no matching rule elsewhere.
     */
    @Test
    void passportPreviewIsCenteredOnPhonesOnly() throws IOException {
        String css = css();
        String phone = media(css, "@media (max-width: 40rem)");
        String centred = rule(phone, "\\.walk-in-guest-body \\.walk-in-doc");
        assertTrue(centred.contains("margin-inline: auto") && centred.contains("width: 100%"), centred);
        assertFalse(Pattern.compile("height|aspect-ratio|margin-top|margin-bottom|position|transform").matcher(centred).find(), centred);
        assertTrue(Pattern.compile("\\.walk-in-doc,\\s*\\.walk-in-room-photo\\s*\\{[^}]*max-width: 16rem").matcher(phone).find(),
                "the box keeps its 16rem cap and 4:3 shape");

        assertFalse(css.replace(phone, "").contains(".walk-in-guest-body .walk-in-doc"), "desktop and tablet are unchanged");
    }

    private static String css() throws IOException {
        return Files.readString(CSS).replaceAll("(?s)/\\*.*?\\*/", "");
    }

    /** Returns the body of the first media block with the given header, found by brace matching. */
    private static String media(String css, String header) {
        int start = css.indexOf(header);
        assertTrue(start >= 0, "missing " + header);
        int open = css.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < css.length(); i++) {
            if (css.charAt(i) == '{') {
                depth++;
            } else if (css.charAt(i) == '}' && --depth == 0) {
                return css.substring(open + 1, i);
            }
        }
        throw new AssertionError("unterminated " + header);
    }

    private static String rule(String css, String selectorRegex) {
        Matcher rule = Pattern.compile("(?:^|[}\\s])" + selectorRegex + "\\s*\\{([^{}]*)}").matcher(css);
        assertTrue(rule.find(), "missing rule " + selectorRegex);
        return rule.group(1);
    }
}
