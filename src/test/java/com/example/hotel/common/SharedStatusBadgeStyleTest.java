package com.example.hotel.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the single PMS status badge (Reservation Detail's approved compact uppercase pill): size, type and shape are
 * defined once in common/layout.css, the status only chooses the colour, and no screen resizes or restyles the badge.
 */
class SharedStatusBadgeStyleTest {

    private static final Path CSS_ROOT = Path.of("src/main/resources/static/css");
    private static final Path TEMPLATE_ROOT = Path.of("src/main/resources/templates");
    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{([^{}]*)}");
    private static final Pattern METRIC = Pattern.compile(
            "(^|[;\\s])(font-size|font-weight|height|min-height|padding[a-z-]*|letter-spacing|text-transform|border-radius)\\s*:");

    /** Confirms the shared base carries Reservation Detail's size and typography, and a small variant for tight places. */
    @Test
    void sharedBadgeHasTheReservationDetailMetrics() throws IOException {
        String base = body(css("common/layout.css"), "\\.status,\\s*\\.status-badge");

        assertTrue(base.contains("font-size: 0.8125rem"), base);
        assertTrue(base.contains("height: 1.75rem"), base);
        assertTrue(base.contains("letter-spacing: 0.03em"), base);
        assertTrue(base.contains("padding: 0 0.75rem"), base);
        assertTrue(base.contains("text-transform: uppercase"), base);
        assertTrue(base.contains("border-radius: 999px"), base);

        String small = body(css("common/layout.css"), "\\.status-badge--small");
        assertTrue(small.contains("height: 1.5rem") && small.contains("padding: 0 0.625rem"), small);
    }

    /** Confirms every reservation status has its Reservation Detail colour in the shared stylesheet. */
    @Test
    void reservationStatusColoursAreShared() throws IOException {
        String layout = css("common/layout.css");

        assertTrue(body(layout, "\\.status-badge--draft").contains("background: #e6e9ee"));
        assertTrue(body(layout, "\\.status-badge--checked_out").contains("background: #e3e8ee"));
        assertTrue(body(layout, "\\.status-badge--checked_out").contains("border-color: #cdd6df"));
        assertTrue(body(layout, "\\.status-badge--no_show").contains("background: #fde9c4"));
        assertTrue(layout.contains(".status-badge--confirmed"));
        assertTrue(layout.contains(".status-badge--checked_in"));
        assertTrue(layout.contains(".status-badge--cancelled"));
    }

    /** Confirms no other stylesheet changes a status badge's size, type or shape (position/margin tweaks are fine). */
    @Test
    void noScreenRestylesTheBadgeMetrics() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CSS_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".css")).toList()) {
                if (file.endsWith("common/layout.css")) {
                    continue;
                }
                Matcher rule = RULE.matcher(css(CSS_ROOT.relativize(file).toString()));
                while (rule.find()) {
                    String selector = rule.group(1).trim();
                    if (selector.contains("status-badge") && METRIC.matcher(rule.group(2)).find()) {
                        offenders.add(CSS_ROOT.relativize(file) + ": " + selector);
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, "Only common/layout.css may size or restyle .status-badge");
    }

    /** Confirms no template still uses the removed Reservation Detail–only badge classes. */
    @Test
    void noTemplateUsesPageLocalBadgeClasses() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATE_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".html")).toList()) {
                String html = Files.readString(file);
                if (html.contains("rd-status") || html.contains("rd-current-badge")) {
                    offenders.add(TEMPLATE_ROOT.relativize(file).toString());
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    private static String css(String relative) throws IOException {
        return Files.readString(CSS_ROOT.resolve(relative)).replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static String body(String css, String selectorRegex) {
        Matcher rule = Pattern.compile("(?:^|[}\\s])(" + selectorRegex + ")\\s*\\{([^{}]*)}").matcher(css);
        assertTrue(rule.find(), "missing rule " + selectorRegex);
        return rule.group(2);
    }
}
