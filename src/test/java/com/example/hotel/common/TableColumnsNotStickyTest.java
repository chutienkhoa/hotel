package com.example.hotel.common;

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
 * Guards the Task33 decision that data tables never use sticky or fixed columns: on narrow screens the whole table,
 * Action column included, scrolls horizontally as one unit. Page-level sticky elements (header, sidebar, bottom action
 * bars) are unaffected because their selectors do not target table cells.
 */
class TableColumnsNotStickyTest {

    private static final Path CSS_ROOT = Path.of("src/main/resources/static/css");
    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{([^{}]*)}");
    private static final Pattern TABLE_SELECTOR = Pattern.compile("(^|[\\s,>+~.])(table|thead|tbody|tfoot|tr|th|td)\\b|-table\\b|table-");
    private static final Pattern PINNED = Pattern.compile("position\\s*:\\s*(sticky|fixed)");

    /** Confirms no stylesheet pins a table, row or cell with position: sticky or position: fixed. */
    @Test
    void noTableSelectorIsStickyOrFixed() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CSS_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".css"))
                    .filter(path -> !path.toString().contains("/vendor/")).toList()) {
                String css = Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "");
                Matcher rule = RULE.matcher(css);
                while (rule.find()) {
                    String selector = rule.group(1).trim();
                    if (PINNED.matcher(rule.group(2)).find() && TABLE_SELECTOR.matcher(selector).find()) {
                        offenders.add(file.getFileName() + ": " + selector);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Sticky/fixed table columns are not allowed: " + offenders);
    }
}
