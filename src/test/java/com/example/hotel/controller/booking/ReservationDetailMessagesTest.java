package com.example.hotel.controller.booking;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Verifies every message key the Reservation Detail family uses exists in BOTH language bundles, so no label can fall
 * back to a raw key and no new English-only text sneaks into the page. (Bundle parity itself is checked by
 * {@code MessageBundlesTest}.)
 */
class ReservationDetailMessagesTest {

    private static final List<String> TEMPLATES = List.of(
            "templates/reservation/detail.html",
            "templates/reservation/detail-fragments.html",
            "templates/reservation/prepayment.html",
            "templates/check-in/reassign.html",
            "templates/layout/feedback-dialog.html");

    private static final Pattern KEY = Pattern.compile("#\\{([A-Za-z0-9_.]+)|msg\\('([A-Za-z0-9_.]+)'");
    private static final List<String> DYNAMIC_ENUMS = List.of(
            "enum.cancellationReasonCode.GUEST_REQUEST", "enum.cancellationReasonCode.CHANGE_OF_PLANS",
            "enum.cancellationReasonCode.DUPLICATE_BOOKING", "enum.cancellationReasonCode.PAYMENT_ISSUE",
            "enum.cancellationReasonCode.HOTEL_OPERATIONAL", "enum.cancellationReasonCode.OTA_CANCELLATION",
            "enum.cancellationReasonCode.OTHER", "enum.reservationStatus.DRAFT", "enum.reservationStatus.CONFIRMED",
            "enum.reservationStatus.CHECKED_IN", "enum.reservationStatus.CHECKED_OUT",
            "enum.reservationStatus.CANCELLED", "enum.reservationStatus.NO_SHOW", "enum.roomChangeReason.GUEST_REQUEST",
            "enum.roomChangeReason.OTHER", "enum.bookingSource.DIRECT", "enum.paymentStatus.PAID",
            "enum.paymentMethod.CASH");

    /** Confirms each key used in the templates (and the dynamic enum labels) resolves in English and Vietnamese. */
    @Test
    void shouldResolveEveryDetailKeyInBothLanguages() throws IOException {
        TreeSet<String> keys = new TreeSet<>(DYNAMIC_ENUMS);
        for (String template : TEMPLATES) {
            Matcher matcher = KEY.matcher(read(template));
            while (matcher.find()) {
                keys.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
            }
        }
        Properties english = load("messages.properties");
        Properties vietnamese = load("messages_vi.properties");

        for (String key : keys) {
            assertTrue(english.containsKey(key), "missing in messages.properties: " + key);
            assertTrue(vietnamese.containsKey(key), "missing in messages_vi.properties: " + key);
        }
    }

    /** Confirms every visible text element of the Detail templates is driven by a message key, never literal text. */
    @Test
    void shouldNotHardCodeVisibleDetailText() throws IOException {
        Pattern element = Pattern.compile(
                "<(h1|h2|label|button|th|dt|dd|p|a|legend)\\b((?:\"[^\"]*\"|'[^']*'|[^>\"'])*)>([^<]*[A-Za-z][^<]*)<");
        for (String template : List.of("templates/reservation/detail.html", "templates/reservation/detail-fragments.html")) {
            Matcher matcher = element.matcher(read(template));
            while (matcher.find()) {
                String attributes = matcher.group(2);
                boolean driven = attributes.contains("th:text") || attributes.contains("th:utext")
                        || attributes.contains("th:replace");
                assertTrue(driven, template + " has literal text: " + matcher.group(3).trim());
            }
        }
    }

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = ReservationDetailMessagesTest.class.getClassLoader().getResourceAsStream(name)) {
            properties.load(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }

    private static String read(String name) throws IOException {
        try (InputStream stream = ReservationDetailMessagesTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
