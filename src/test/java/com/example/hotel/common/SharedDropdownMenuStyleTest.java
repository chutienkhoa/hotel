package com.example.hotel.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Guards the single PMS open-menu design: the light palette, hover, selected and disabled states are defined once in
 * common/layout.css for every native select, and no screen restyles the menu or redefines the palette, so a new select
 * automatically looks like all the others.
 */
class SharedDropdownMenuStyleTest {

    private static final Path CSS_ROOT = Path.of("src/main/resources/static/css");
    private static final Path TEMPLATE_ROOT = Path.of("src/main/resources/templates");
    private static final String SELECT = "select:not\\(\\[multiple\\]\\):not\\(\\[size\\]\\)";

    /** Confirms the shared palette is the light PMS one, as specified. */
    @Test
    void sharedPaletteIsLight() throws IOException {
        String layout = css("common/layout.css");

        assertTrue(layout.contains("--color-dropdown-surface: #ffffff"));
        assertTrue(layout.contains("--color-dropdown-border: #d7e2ee"));
        assertTrue(layout.contains("--color-dropdown-text: var(--color-navy)"));
        assertTrue(layout.contains("--color-dropdown-hover: #f1f6fa"));
        assertTrue(layout.contains("--color-dropdown-selected: #e6f2fb"));
        assertTrue(layout.contains("--color-dropdown-selected-text: var(--color-primary)"));
        assertTrue(layout.contains("--color-dropdown-disabled-text: var(--color-muted)"));
        assertFalse(layout.contains("#2c2f33"), "the old dark menu surface must be gone");
    }

    /** Confirms the shared popup defines panel, hover, selected (with check mark) and disabled states. */
    @Test
    void sharedPopupDefinesEveryState() throws IOException {
        String layout = css("common/layout.css");

        assertTrue(rule(layout, SELECT + "::picker\\(select\\)", "border-radius: var(--radius-md)").contains(
                "background: var(--color-dropdown-surface)"));
        assertTrue(rule(layout, SELECT + " option:hover,\\s*" + SELECT + " option:focus-visible", "background")
                .contains("--color-dropdown-hover"));
        String selected = rule(layout, SELECT + " option:checked", "font-weight: 600");
        assertTrue(selected.contains("background: var(--color-dropdown-selected)")
                && selected.contains("color: var(--color-dropdown-selected-text)"), selected);
        assertTrue(rule(layout, SELECT + " option::checkmark,\\s*" + SELECT + " option:checked::checkmark", "color")
                .contains("--color-dropdown-selected-text"));
        String disabled = rule(layout, SELECT + " option:disabled,[^{]*", "cursor: not-allowed");
        assertTrue(disabled.contains("--color-dropdown-disabled-text")
                && disabled.contains("--color-dropdown-disabled-surface"), disabled);
    }

    /** Confirms only layout.css defines the open select menu or the palette tokens; screens must reuse them. */
    @Test
    void noScreenRestylesTheMenuOrRedefinesThePalette() throws IOException {
        Pattern pickerRule = Pattern.compile("::picker\\(|(?<![\\w.#-])option(?![\\w-])[^{,]*\\{");
        Pattern tokenDefinition = Pattern.compile("--color-dropdown-[a-z-]+\\s*:");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CSS_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".css")).toList()) {
                if (file.endsWith("common/layout.css")) {
                    continue;
                }
                String css = stripComments(Files.readString(file));
                for (Pattern forbidden : List.of(pickerRule, tokenDefinition)) {
                    Matcher match = forbidden.matcher(css);
                    while (match.find()) {
                        offenders.add(CSS_ROOT.relativize(file) + ": " + match.group());
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, "Only common/layout.css may style the open select menu");
    }

    /** Confirms no template styles a select inline, which would bypass the shared design. */
    @Test
    void noSelectIsStyledInline() throws IOException {
        Pattern inlineStyledSelect = Pattern.compile("<select\\b[^>]*\\bstyle\\s*=");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATE_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".html")).toList()) {
                if (inlineStyledSelect.matcher(Files.readString(file)).find()) {
                    offenders.add(TEMPLATE_ROOT.relativize(file).toString());
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    private static String css(String relative) throws IOException {
        return stripComments(Files.readString(CSS_ROOT.resolve(relative)));
    }

    private static String stripComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    /** Returns the declaration block of a rule whose selector matches {@code selectorRegex} and that contains {@code needle}. */
    private static String rule(String css, String selectorRegex, String needle) {
        Matcher rule = Pattern.compile("(?:^|[}\\s,{])(" + selectorRegex + ")\\s*\\{([^{}]*)}").matcher(css);
        while (rule.find()) {
            if (rule.group(2).contains(needle)) {
                return rule.group(2);
            }
        }
        throw new AssertionError("missing rule /" + selectorRegex + "/ containing " + needle);
    }
}
