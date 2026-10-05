package com.example.hotel.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the phone layout of the Walk-in / OTA room selection table: the Status column is wide enough to hold its whole badge and
 * the Rate column (header and cells) has extra left padding, so the badge never touches the price field; done with column widths
 * and cell padding only (no margins, transforms or font changes), and only at the phone breakpoint.
 */
class RoomTableMobileLayoutTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/front-desk/walk-in.css");

    /** Confirms the phone block widens the table and Status column and pads the Rate column, with widths adding up to 100%. */
    @Test
    void phoneLayoutSeparatesTheStatusBadgeFromThePriceInput() throws IOException {
        String block = media(css(), "@media (max-width: 40rem)");
        assertTrue(rule(block, "\\.walk-in-room-table").contains("min-width: 42rem"));

        int total = 0;
        for (int column = 1; column <= 6; column++) {
            Matcher width = Pattern.compile("\\.walk-in-room-table th:nth-child\\(" + column + "\\)\\s*\\{\\s*width:\\s*(\\d+)%").matcher(block);
            assertTrue(width.find(), "width for column " + column);
            total += Integer.parseInt(width.group(1));
        }
        assertEquals(100, total, "column widths add up to the whole table");

        String rate = rule(block, "\\.walk-in-room-table th:nth-child\\(6\\),\\s*\\.walk-in-room-table td:nth-child\\(6\\)");
        assertTrue(rate.contains("padding-left: var(--space-3)"), "header and cells of the Rate column share the extra left padding");
    }

    /** Confirms the phone block uses no margin, transform or font tweak on the table, and the desktop rules are unchanged. */
    @Test
    void onlyWidthsAndPaddingChangeAndOnlyOnPhones() throws IOException {
        String css = css();
        String block = media(css, "@media (max-width: 40rem)");
        Matcher tableRules = Pattern.compile("(\\.walk-in-room-table[^{]*)\\{([^{}]*)}").matcher(block);
        while (tableRules.find()) {
            assertFalse(Pattern.compile("margin|transform|font-size|position").matcher(tableRules.group(2)).find(),
                    tableRules.group(1) + " must only set widths / padding");
        }
        // Desktop and tablet keep the original table.
        assertTrue(rule(css, "\\.walk-in-room-table").contains("min-width: 34rem"));
        assertTrue(css.contains(".walk-in-room-table th:nth-child(5) { width: 16%; }")
                && css.contains(".walk-in-room-table th:nth-child(6) { width: 22%; }"));
    }

    private static String css() throws IOException {
        return Files.readString(CSS).replaceAll("(?s)/\\*.*?\\*/", "");
    }

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
        Matcher rule = Pattern.compile("(?:^|[}\\s,])" + selectorRegex + "\\s*\\{([^{}]*)}").matcher(css);
        assertTrue(rule.find(), "missing rule " + selectorRegex);
        return rule.group(1);
    }
}
