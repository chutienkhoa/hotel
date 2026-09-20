package com.example.hotel.common.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/** Guards the EN/VI bundles: same keys, same message arguments, no blanks, and the English fallback. */
class MessageBundlesTest {

    private static final Pattern ARGUMENT = Pattern.compile("\\{(\\d+|[a-zA-Z]+)}");

    private static Map<String, String> load(String resource) throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = MessageBundlesTest.class.getResourceAsStream(resource);
                InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Map<String, String> values = new TreeMap<>();
        properties.forEach((key, value) -> values.put((String) key, (String) value));
        return values;
    }

    private static Set<String> arguments(String message) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = ARGUMENT.matcher(message);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /** Confirms both bundles define exactly the same keys. */
    @Test
    void shouldContainTheSameKeysInEnglishAndVietnamese() throws Exception {
        Set<String> english = load("/messages.properties").keySet();
        Set<String> vietnamese = load("/messages_vi.properties").keySet();

        Set<String> missingInVietnamese = new TreeSet<>(english);
        missingInVietnamese.removeAll(vietnamese);
        Set<String> missingInEnglish = new TreeSet<>(vietnamese);
        missingInEnglish.removeAll(english);
        assertEquals(Set.of(), missingInVietnamese, "keys missing in messages_vi.properties");
        assertEquals(Set.of(), missingInEnglish, "keys missing in messages.properties");
    }

    /** Confirms every message is non-blank and keeps the same argument placeholders in both languages. */
    @Test
    void shouldKeepArgumentPlaceholdersAndNonBlankValues() throws Exception {
        Map<String, String> english = load("/messages.properties");
        Map<String, String> vietnamese = load("/messages_vi.properties");
        for (Map.Entry<String, String> entry : english.entrySet()) {
            String vi = vietnamese.get(entry.getKey());
            assertFalse(entry.getValue().isBlank(), entry.getKey());
            assertFalse(vi.isBlank(), entry.getKey());
            assertEquals(arguments(entry.getValue()), arguments(vi), "placeholders of " + entry.getKey());
        }
    }

    /** Confirms keys follow the approved semantic families and never look like English sentences. */
    @Test
    void shouldUseApprovedKeyFamilies() throws Exception {
        Set<String> families = Set.of("common", "navigation", "table", "auth", "dashboard", "report", "reservation", "guest",
                "room", "checkin", "checkout", "payment", "expense", "revenue", "staff", "user", "role", "validation",
                "error", "enum", "js");
        for (String key : load("/messages.properties").keySet()) {
            assertTrue(families.contains(key.substring(0, key.indexOf('.'))), key);
            assertFalse(key.contains(" "), key);
        }
    }

    /** Confirms a key missing from the Vietnamese bundle falls back to English, never to the raw key. */
    @Test
    void shouldFallBackToEnglishForMissingVietnameseKey() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n-fallback/fallbacktest");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        UiMessages messages = new UiMessages(source);

        assertEquals("Tiếng Việt cả hai", messages.get(Locale.of("vi"), "sample.both"));
        assertEquals("English only text", messages.get(Locale.of("vi"), "sample.englishOnly"));
        assertEquals("English only text", messages.get(Locale.ENGLISH, "sample.englishOnly"));
    }
}
