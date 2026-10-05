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
 * Guards that Create Reservation keeps the same breadcrumb -> page-title gap as Front Desk (the approved reference), is not
 * collapsed to zero, and gets it from CSS rather than spacer markup.
 */
class BreadcrumbTitleSpacingTest {

    private static final Path CSS = Path.of("src/main/resources/static/css");
    private static final Path CREATE_TEMPLATE = Path.of("src/main/resources/templates/reservation/create.html");

    /** Confirms both pages use the same spacing token between the breadcrumb and the page header. */
    @Test
    void createReservationMatchesFrontDeskBreadcrumbToTitleGap() throws IOException {
        String frontDesk = stripComments(Files.readString(CSS.resolve("front-desk/front-desk.css")));
        String create = stripComments(Files.readString(CSS.resolve("reservation/create.css")));

        String reference = declaration(frontDesk, "\\.front-desk > \\* \\+ \\*", "margin-top");
        String gap = declaration(create, "\\.reservation-create > \\.breadcrumb \\+ \\.page-header", "margin-top");

        assertEquals("var(--space-5)", reference);
        assertEquals(reference, gap, "Create Reservation must use Front Desk's breadcrumb -> title gap");
        String list = stripComments(Files.readString(CSS.resolve("reservation/list.css")));
        assertEquals(reference, declaration(list, "\\.reservation-list > \\.breadcrumb \\+ \\.page-header", "margin-top"),
                "the Reservation List must use the same breadcrumb -> title gap");
        assertFalse(create.matches("(?s).*\\.reservation-create \\.breadcrumb\\s*\\{[^}]*margin-bottom:\\s*0.*"),
                "the breadcrumb's shared bottom margin must not be zeroed");
    }

    /** Confirms the gap is not produced by line breaks or empty spacer elements. */
    @Test
    void noSpacerMarkupBetweenBreadcrumbAndTitle() throws IOException {
        String html = Files.readString(CREATE_TEMPLATE);
        String between = html.substring(html.indexOf("layout/breadcrumb :: crumbs"), html.indexOf("<h1"));

        assertFalse(between.contains("<br"), between);
        assertFalse(Pattern.compile("<div[^>]*>\\s*</div>").matcher(between).find(), between);
    }

    private static String declaration(String css, String selectorRegex, String property) {
        Matcher rule = Pattern.compile("(?:^|[}\\s])" + selectorRegex + "\\s*\\{([^{}]*)}").matcher(css);
        assertTrue(rule.find(), "missing rule " + selectorRegex);
        Matcher value = Pattern.compile(property + "\\s*:\\s*([^;]+);").matcher(rule.group(1));
        assertTrue(value.find(), property + " in " + selectorRegex);
        return value.group(1).trim();
    }

    private static String stripComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }
}
