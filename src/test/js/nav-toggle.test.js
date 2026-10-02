"use strict";

/*
 * Regression tests for static/js/common/nav-toggle.js (Task33 Batch 1A mobile sidebar drawer).
 *
 * Run with: node src/test/js/nav-toggle.test.js
 */

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const SOURCE_PATH = path.join(
    __dirname, "..", "..", "main", "resources", "static", "js", "common", "nav-toggle.js");

/** Creates a stub DOM node that records listeners, attributes and dataset, like money-input.test.js's. */
function createNode() {
    const attributes = {};
    return {
        dataset: {},
        listeners: {},
        focusCount: 0,
        attributes,
        addEventListener(type, handler) {
            (this.listeners[type] = this.listeners[type] || []).push(handler);
        },
        dispatch(type, event) {
            (this.listeners[type] || []).forEach((handler) => handler(event || {}));
        },
        getAttribute(name) {
            return Object.prototype.hasOwnProperty.call(attributes, name) ? attributes[name] : null;
        },
        setAttribute(name, value) {
            attributes[name] = value;
        },
        removeAttribute(name) {
            delete attributes[name];
        },
        focus() {
            this.focusCount++;
        },
    };
}

/** Loads nav-toggle.js against stub toggle/sidebar/backdrop elements, exactly as a page would. */
function loadNavToggle({ missing } = {}) {
    const toggle = createNode();
    toggle.setAttribute("aria-expanded", "false");
    toggle.dataset.labelOpen = "Open navigation menu";
    toggle.dataset.labelClose = "Close navigation menu";
    const sidebar = createNode();
    const backdrop = createNode();
    const documentStub = createNode();
    documentStub.getElementById = (id) => {
        if (missing === id) {
            return null;
        }
        return { "header-nav-toggle": toggle, "sidebar-nav": sidebar, "sidebar-backdrop": backdrop }[id] || null;
    };

    vm.runInNewContext(
        fs.readFileSync(SOURCE_PATH, "utf8"), { document: documentStub }, { filename: SOURCE_PATH });
    return { toggle, sidebar, backdrop, documentStub };
}

let failures = 0;

/** Records one assertion result and prints it. */
function assertEquals(expected, actual, description) {
    if (expected === actual) {
        console.log(`  ok   ${description}`);
        return;
    }
    failures++;
    console.log(`  FAIL ${description}\n         expected ${JSON.stringify(expected)}`
        + ` but was ${JSON.stringify(actual)}`);
}

console.log("nav-toggle.js");

console.log(" opening sets aria-expanded, opens the drawer and backdrop, and moves focus in");
{
    const { toggle, sidebar, backdrop } = loadNavToggle();
    toggle.dispatch("click");
    assertEquals("true", toggle.getAttribute("aria-expanded"), "toggle aria-expanded becomes true");
    assertEquals("Close navigation menu", toggle.getAttribute("aria-label"), "toggle aria-label swaps to close");
    assertEquals("true", sidebar.getAttribute("data-open"), "sidebar gets data-open=true");
    assertEquals("true", backdrop.getAttribute("data-open"), "backdrop gets data-open=true");
    assertEquals(1, sidebar.focusCount, "focus moves into the open sidebar");
}

console.log(" clicking again closes the drawer and backdrop");
{
    const { toggle, sidebar, backdrop } = loadNavToggle();
    toggle.dispatch("click");
    toggle.dispatch("click");
    assertEquals("false", toggle.getAttribute("aria-expanded"), "toggle aria-expanded returns to false");
    assertEquals("Open navigation menu", toggle.getAttribute("aria-label"), "toggle aria-label swaps back to open");
    assertEquals(null, sidebar.getAttribute("data-open"), "sidebar's data-open is removed");
    assertEquals(null, backdrop.getAttribute("data-open"), "backdrop's data-open is removed");
}

console.log(" clicking the backdrop closes the drawer and returns focus to the toggle");
{
    const { toggle, sidebar, backdrop } = loadNavToggle();
    toggle.dispatch("click");
    backdrop.dispatch("click");
    assertEquals("false", toggle.getAttribute("aria-expanded"), "toggle closes after a backdrop click");
    assertEquals(null, sidebar.getAttribute("data-open"), "sidebar closes after a backdrop click");
    assertEquals(1, toggle.focusCount, "focus returns to the toggle button");
}

console.log(" Escape closes an open drawer and returns focus to the toggle, but does nothing when already closed");
{
    const { toggle, documentStub } = loadNavToggle();
    documentStub.dispatch("keydown", { key: "Escape" });
    assertEquals("false", toggle.getAttribute("aria-expanded"), "Escape is a no-op while already closed");
    assertEquals(0, toggle.focusCount, "Escape does not steal focus while already closed");

    toggle.dispatch("click");
    documentStub.dispatch("keydown", { key: "Escape" });
    assertEquals("false", toggle.getAttribute("aria-expanded"), "Escape closes the open drawer");
    assertEquals(1, toggle.focusCount, "Escape returns focus to the toggle button");
}

console.log(" a non-Escape key while open is ignored");
{
    const { toggle, documentStub } = loadNavToggle();
    toggle.dispatch("click");
    documentStub.dispatch("keydown", { key: "Enter" });
    assertEquals("true", toggle.getAttribute("aria-expanded"), "a non-Escape key leaves the drawer open");
}

console.log(" loading against a page missing any of the three elements never throws (desktop/tablet has no toggle)");
["header-nav-toggle", "sidebar-nav", "sidebar-backdrop"].forEach((missingId) => {
    loadNavToggle({ missing: missingId });
    console.log(`  ok   does not throw when #${missingId} is absent`);
});

console.log(failures === 0 ? "\nAll nav-toggle.js tests passed." : `\n${failures} nav-toggle.js test(s) FAILED.`);
process.exit(failures === 0 ? 0 : 1);
