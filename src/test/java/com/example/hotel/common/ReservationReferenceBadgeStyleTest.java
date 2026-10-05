package com.example.hotel.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the Reservation Detail reservation-number badge: one status-independent rule (gray pill, white text) shared by
 * every status, so a status can never restyle it, and the contrast stays above the 4.5:1 text minimum.
 */
class ReservationReferenceBadgeStyleTest {

    private static final Path DETAIL_CSS = Path.of("src/main/resources/static/css/reservation/detail.css");

    /** Confirms the badge is a gray pill with white text and no visible border. */
    @Test
    void referenceBadgeIsGrayWithWhiteText() throws IOException {
        String css = Files.readString(DETAIL_CSS).replaceAll("(?s)/\\*.*?\\*/", "");
        Matcher rule = Pattern.compile("\\.rd-reference\\s*\\{([^{}]*)}").matcher(css);
        assertTrue(rule.find());
        String body = rule.group(1);

        assertTrue(body.contains("background: #667085"), body);
        assertTrue(body.contains("color: #ffffff"), body);
        assertTrue(body.contains("border: 1px solid transparent"), body);
        assertTrue(body.contains("white-space: nowrap"), body);
        assertTrue(body.contains("border-radius: 999px"), body);
        assertTrue(contrast("667085", "ffffff") >= 4.5, "white on the gray must stay readable");
    }

    /** Confirms there is exactly one rule for the badge and no per-status variant. */
    @Test
    void referenceBadgeHasNoStatusVariants() throws IOException {
        String css = Files.readString(DETAIL_CSS).replaceAll("(?s)/\\*.*?\\*/", "");
        Matcher selectors = Pattern.compile("\\.rd-reference[^{,]*\\{").matcher(css);
        int count = 0;
        while (selectors.find()) {
            count++;
        }
        assertEquals(1, count, "the badge must be one shared rule, not status-specific copies");
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
