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
 * Guards the guest actions on the Walk-in and OTA entry forms: "Search Existing Guest" stays the stronger blue outline and
 * "Create New Guest" is the quieter shared subtle-blue outline, defined once in the shared button styles (no screen-specific
 * button CSS for it), with readable contrast.
 */
class GuestActionButtonsStyleTest {

    private static final Path CSS = Path.of("src/main/resources/static/css");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates/check-in");

    /** Confirms the shared subtle outline: white background, one subtle blue for border, text and icon, light-blue hover. */
    @Test
    void subtleOutlineIsOneSharedVariant() throws IOException {
        String layout = Files.readString(CSS.resolve("common/layout.css")).replaceAll("(?s)/\\*.*?\\*/", "");

        assertTrue(layout.contains("--color-primary-subtle: #2b78b6"));
        String base = rule(layout, "\\.button-outline-subtle");
        assertTrue(base.contains("background: var(--color-surface)"), base);
        assertTrue(base.contains("border-color: var(--color-primary-subtle)") && base.contains("color: var(--color-primary-subtle)"), base);
        String hover = rule(layout, "\\.button-outline-subtle:hover");
        assertTrue(hover.contains("background: var(--color-info-surface)"), hover);
        assertFalse(hover.contains("background: var(--color-primary)"), "never a solid blue hover");
        assertTrue(contrast("2b78b6", "ffffff") >= 4.5, "the label must stay readable on white");
    }

    /** Confirms both pages use the shared variant for Create New Guest and leave Search Existing Guest as it was. */
    @Test
    void bothPagesStyleCreateNewGuestAsTheSecondaryAction() throws IOException {
        for (String page : List.of("walk-in.html", "ota-entry.html")) {
            String html = Files.readString(TEMPLATES.resolve(page));
            assertTrue(html.contains("class=\"button button-outline-subtle walk-in-new-guest\""), page);
            assertTrue(html.contains("button button-secondary walk-in-search-toggle"), page + ": search is unchanged");
        }
        String walkIn = Files.readString(CSS.resolve("front-desk/walk-in.css")).replaceAll("(?s)/\\*.*?\\*/", "");
        assertFalse(walkIn.contains(".walk-in-new-guest"), "no screen-specific CSS for Create New Guest");
        assertTrue(rule(walkIn, "\\.walk-in-search-toggle").contains("border-color: var(--color-primary)"),
                "Search Existing Guest keeps the stronger primary blue");
    }

    private static String rule(String css, String selectorRegex) {
        Matcher rule = Pattern.compile("(?:^|[}\\s])" + selectorRegex + "\\s*\\{([^{}]*)}").matcher(css);
        assertTrue(rule.find(), "missing rule " + selectorRegex);
        return rule.group(1);
    }

    private static double contrast(String foreground, String background) {
        double a = luminance(foreground);
        double b = luminance(background);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(String hex) {
        double[] channel = new double[3];
        for (int i = 0; i < 3; i++) {
            double value = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16) / 255.0;
            channel[i] = value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * channel[0] + 0.7152 * channel[1] + 0.0722 * channel[2];
    }
}
