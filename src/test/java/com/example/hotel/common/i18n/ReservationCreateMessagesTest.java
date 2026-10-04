package com.example.hotel.common.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the Create Reservation page's text: every message key its template (page text and the browser messages passed
 * to create.js) and its DTO constraints use exists in both the English and Vietnamese bundles.
 */
class ReservationCreateMessagesTest {

    private static final Pattern TEMPLATE_KEY = Pattern.compile("#\\{([A-Za-z0-9_.]+)[})]");
    private static final Pattern CLIENT_MESSAGE_KEY = Pattern.compile("'[A-Za-z0-9]+':'([A-Za-z0-9_.]+)'");
    private static final Pattern CONSTRAINT_KEY = Pattern.compile("\\{((?:validation|typeMismatch)[A-Za-z0-9_.]+)}");

    private static String read(String resource) throws Exception {
        try (InputStream stream = ReservationCreateMessagesTest.class.getResourceAsStream(resource)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Properties bundle(String resource) throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = ReservationCreateMessagesTest.class.getResourceAsStream(resource);
                InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static Set<String> keys(Pattern pattern, String text) {
        Set<String> keys = new TreeSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    /** Confirms no key used by the page or the create DTO is missing from either language. */
    @Test
    void shouldDefineEveryKeyTheCreatePageUsesInBothLanguages() throws Exception {
        Set<String> used = new TreeSet<>();
        String template = read("/templates/reservation/create.html");
        used.addAll(keys(TEMPLATE_KEY, template));
        used.addAll(keys(CLIENT_MESSAGE_KEY, template));
        used.addAll(keys(CONSTRAINT_KEY, readSource("dto/booking/request/CreateRequest.java")));
        assertTrue(used.size() > 80, "expected the template's message keys to be found, got " + used.size());

        Properties english = bundle("/messages.properties");
        Properties vietnamese = bundle("/messages_vi.properties");
        Set<String> missing = new TreeSet<>();
        for (String key : used) {
            if (!english.containsKey(key) || !vietnamese.containsKey(key)) {
                missing.add(key);
            }
        }
        assertEquals(Set.of(), missing, "keys missing from a bundle");
    }

    /** Confirms the server rejection keys the service raises have a text in both languages. */
    @Test
    void shouldDefineEveryCreateRejectionKeyInBothLanguages() throws Exception {
        Set<String> raised = keys(Pattern.compile("\"(reservation\\.create\\.error\\.[A-Za-z]+)\""),
                readSource("service/booking/ReservationService.java"));
        assertTrue(raised.size() >= 9, "expected the service's rejection keys, got " + raised);
        Properties english = bundle("/messages.properties");
        Properties vietnamese = bundle("/messages_vi.properties");
        for (String key : raised) {
            assertTrue(english.containsKey(key), key);
            assertTrue(vietnamese.containsKey(key), key);
        }
    }

    private static String readSource(String relativePath) throws Exception {
        return java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/example/hotel", relativePath), StandardCharsets.UTF_8);
    }
}
