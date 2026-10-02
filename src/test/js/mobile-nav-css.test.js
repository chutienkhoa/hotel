"use strict";

/*
 * Regression test for the Task33 mobile nav trigger visibility bug: components.css declared an
 * unconditional `display: none` for .header-nav-toggle, loading after layout.css (see
 * templates/layout/base.html) and therefore always winning the cascade over layout.css's
 * `@media (max-width: 48rem) { .header-nav-toggle { display: inline-flex; } }` override -- at every
 * viewport, including mobile, where the hamburger trigger must be visible. The fix keeps both the
 * base-hide and the mobile-show rule together in layout.css (the same pattern already used by
 * .sidebar-backdrop) and removes any `display` declaration for this selector from components.css.
 *
 * This test reads the actual CSS source text (not a full CSS parser -- just enough structure to catch
 * this exact class of cross-file cascade bug) rather than executing JavaScript.
 *
 * Run with: node src/test/js/mobile-nav-css.test.js
 */

const fs = require("node:fs");
const path = require("node:path");

const LAYOUT_CSS_PATH = path.join(
    __dirname, "..", "..", "main", "resources", "static", "css", "common", "layout.css");
const COMPONENTS_CSS_PATH = path.join(
    __dirname, "..", "..", "main", "resources", "static", "css", "common", "components.css");

const layoutCss = fs.readFileSync(LAYOUT_CSS_PATH, "utf8");
const componentsCss = fs.readFileSync(COMPONENTS_CSS_PATH, "utf8");

let failures = 0;

/** Records one assertion result and prints it. */
function assertTrue(condition, description) {
    if (condition) {
        console.log(`  ok   ${description}`);
        return;
    }
    failures++;
    console.log(`  FAIL ${description}`);
}

console.log("mobile nav CSS cascade (layout.css + components.css)");

const mobileMediaIndex = layoutCss.indexOf("@media (max-width: 48rem)");
const baseRuleMatch = /\.header-nav-toggle\s*\{[^}]*\}/.exec(layoutCss);
const baseRuleIndex = baseRuleMatch ? baseRuleMatch.index : -1;

console.log(" layout.css declares an unconditional base .header-nav-toggle { display: none } rule");
assertTrue(baseRuleIndex !== -1, "a bare .header-nav-toggle rule exists in layout.css");
assertTrue(
    baseRuleIndex !== -1 && /display\s*:\s*none/.test(baseRuleMatch[0]),
    "that base rule sets display: none");
assertTrue(
    baseRuleIndex !== -1 && mobileMediaIndex !== -1 && baseRuleIndex < mobileMediaIndex,
    "the base rule appears before the @media (max-width: 48rem) block, so the media override wins at mobile widths");

console.log(" layout.css's <=48rem media block shows the toggle");
const mobileBlockMatch = /@media \(max-width: 48rem\) \{([\s\S]*?)\n\}\n/.exec(layoutCss);
const mobileBlock = mobileBlockMatch ? mobileBlockMatch[1] : "";
const mobileToggleRuleMatch = /\.header-nav-toggle\s*\{([^}]*)\}/.exec(mobileBlock);
assertTrue(mobileToggleRuleMatch !== null, "the <=48rem block contains its own .header-nav-toggle rule");
assertTrue(
    mobileToggleRuleMatch !== null && /display\s*:\s*inline-flex/.test(mobileToggleRuleMatch[1]),
    "that rule sets display: inline-flex, making the trigger visible on mobile");

console.log(" components.css (loaded after layout.css) never declares a competing display for the toggle");
const componentsBaseRuleMatch = /\.header-nav-toggle\s*\{([^}]*)\}/.exec(componentsCss);
assertTrue(
    componentsBaseRuleMatch !== null && !/display\s*:/.test(componentsBaseRuleMatch[1]),
    "components.css's .header-nav-toggle rule has no display declaration at all (avoids defeating layout.css's cascade)");

console.log(failures === 0
    ? "\nAll mobile-nav-css tests passed."
    : `\n${failures} mobile-nav-css test(s) FAILED.`);
process.exit(failures === 0 ? 0 : 1);
