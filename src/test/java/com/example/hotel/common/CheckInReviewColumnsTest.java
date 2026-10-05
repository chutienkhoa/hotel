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
 * Guards the Check-in Review two-column flow: Room Assignment then Stay Details in the left column, Room Status,
 * Prepayments and Documents in the right column, each column sizing to its own content (no shared grid rows, fixed
 * heights or positioning workarounds), and the original reading order restored when the columns flatten on narrow screens.
 */
class CheckInReviewColumnsTest {

    private static final Path CSS = Path.of("src/main/resources/static/css/front-desk/check-in-review.css");
    private static final Path TEMPLATE = Path.of("src/main/resources/templates/check-in/review.html");

    /** Confirms each card is in the intended column, in order, inside one start-aligned container. */
    @Test
    void cardsFlowInTwoIndependentColumns() throws IOException {
        String html = Files.readString(TEMPLATE);
        int container = html.indexOf("cir-pair cir-pair--columns");
        int main = html.indexOf("cir-column cir-column--main", container);
        int side = html.indexOf("cir-column cir-column--side", main);
        assertTrue(container > 0 && container < main && main < side);

        List<String> mainIds = List.of("room-assignment", "stay-details");
        List<String> sideIds = List.of("arrival-readiness", "prepayment-summary", "documents");
        int previous = main;
        for (String id : mainIds) {
            int at = html.indexOf("id=\"" + id + "\"", previous);
            assertTrue(at > previous && at < side, id + " belongs to the main column, in order");
            previous = at;
        }
        previous = side;
        int end = html.indexOf("<!-- Final action", side);
        for (String id : sideIds) {
            int at = html.indexOf("id=\"" + id + "\"", previous);
            assertTrue(at > previous && at < end, id + " belongs to the side column, in order");
            previous = at;
        }
        assertEquals(html.split("id=\"room-assignment\"", -1).length, 2, "the room card is rendered once");
    }

    /** Confirms the columns align to the start and nothing fixes heights or pulls cards with positioning tricks. */
    @Test
    void columnsSizeToTheirContentWithoutWorkarounds() throws IOException {
        String css = css();
        Matcher columns = Pattern.compile("\\.cir-pair--columns\\s*\\{([^{}]*)}").matcher(css);
        assertTrue(columns.find());
        assertTrue(columns.group(1).contains("align-items: start"), columns.group(1));

        Matcher rules = Pattern.compile("([^{}]+)\\{([^{}]*)}").matcher(css);
        while (rules.find()) {
            String selector = rules.group(1);
            if (selector.contains("#room-assignment") || selector.contains("#stay-details")
                    || selector.contains(".cir-column") || selector.contains(".cir-pair--columns")
                    || selector.contains(".cir-rooms") || selector.trim().equals(".cir-room")) {
                String body = rules.group(2);
                assertFalse(Pattern.compile("(^|[;\\s])(height|min-height|flex-grow|flex)\\s*:").matcher(body).find(),
                        selector + " must not fix height or grow");
                assertFalse(body.contains("position: absolute") || Pattern.compile("margin[a-z-]*:\\s*-").matcher(body).find(),
                        selector + " must not use absolute positioning or negative margins");
            }
        }
    }

    /** Confirms below 64rem the columns dissolve and the cards stack Room Assignment, Room Status, Stay, Prepayments, Documents. */
    @Test
    void narrowScreensKeepTheReadingOrder() throws IOException {
        String css = css();
        int media = css.indexOf("@media (max-width: 64rem)");
        String block = css.substring(media, css.indexOf("@media", media + 10));

        assertTrue(Pattern.compile("\\.cir-column--main,\\s*\\.cir-column--side\\s*\\{[^}]*display: contents").matcher(block).find());
        int last = 0;
        for (String id : List.of("room-assignment", "arrival-readiness", "stay-details", "prepayment-summary", "documents")) {
            Matcher order = Pattern.compile("#" + id + "\\s*\\{\\s*order:\\s*(\\d+)").matcher(block);
            assertTrue(order.find(), id + " has an order");
            int value = Integer.parseInt(order.group(1));
            assertTrue(value > last, id + " is stacked in reading order");
            last = value;
        }
    }

    private static String css() throws IOException {
        return Files.readString(CSS).replaceAll("(?s)/\\*.*?\\*/", "");
    }
}
