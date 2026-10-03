"use strict";

/*
 * Regression tests for static/js/common/sidebar-collapse.js (desktop sidebar collapse/expand with a
 * localStorage-remembered state).
 *
 * Run with: node src/test/js/sidebar-collapse.test.js
 */

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const SOURCE_PATH = path.join(
    __dirname, "..", "..", "main", "resources", "static", "js", "common", "sidebar-collapse.js");
const STORAGE_KEY = "pms.sidebar.collapsed";

/** Minimal classList backed by a Set, enough for add/contains/toggle. */
function createClassList() {
    const names = new Set();
    return {
        add: (name) => names.add(name),
        contains: (name) => names.has(name),
        toggle(name, force) {
            const on = force === undefined ? !names.has(name) : force;
            if (on) {
                names.add(name);
            } else {
                names.delete(name);
            }
            return on;
        },
    };
}

/** Creates a stub element that records attributes, dataset and listeners. */
function createElement(extra = {}) {
    const attributes = {};
    const listeners = {};
    return Object.assign({
        dataset: {},
        attributes,
        getAttribute: (name) => (Object.prototype.hasOwnProperty.call(attributes, name) ? attributes[name] : null),
        setAttribute: (name, value) => { attributes[name] = String(value); },
        removeAttribute: (name) => { delete attributes[name]; },
        addEventListener: (type, handler) => { (listeners[type] = listeners[type] || []).push(handler); },
        click() { (listeners.click || []).forEach((handler) => handler({})); },
    }, extra);
}

/** Loads sidebar-collapse.js against a stub page; `storedValue` seeds localStorage (null = empty). */
function loadSidebarCollapse({ storedValue = null, storageBlocked = false } = {}) {
    const root = { classList: createClassList() };
    const toggle = createElement({ dataset: { labelCollapse: "Collapse sidebar", labelExpand: "Expand sidebar" } });
    toggle.setAttribute("aria-expanded", "true");
    toggle.setAttribute("aria-label", "Collapse sidebar");
    const links = ["Dashboard", "Room List"].map((text) => createElement({
        querySelector: () => ({ textContent: ` ${text} ` }),
    }));
    const sidebar = createElement({ querySelectorAll: () => links });
    const storage = {
        values: storedValue === null ? {} : { [STORAGE_KEY]: storedValue },
        getItem(key) {
            if (storageBlocked) {
                throw new Error("SecurityError");
            }
            return Object.prototype.hasOwnProperty.call(this.values, key) ? this.values[key] : null;
        },
        setItem(key, value) {
            if (storageBlocked) {
                throw new Error("SecurityError");
            }
            this.values[key] = value;
        },
    };
    const documentStub = createElement({
        documentElement: root,
        readyState: "complete",
        getElementById: (id) => ({ "sidebar-collapse-toggle": toggle, "sidebar-nav": sidebar }[id] || null),
    });

    vm.runInNewContext(
        fs.readFileSync(SOURCE_PATH, "utf8"),
        { document: documentStub, localStorage: storage },
        { filename: SOURCE_PATH });
    return { root, toggle, links, storage };
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

console.log("sidebar-collapse.js");

{
    const { root } = loadSidebarCollapse();
    assertEquals(false, root.classList.contains("sidebar-collapsed"), "starts expanded when nothing is stored");
}

{
    const { root, toggle } = loadSidebarCollapse({ storedValue: "true" });
    assertEquals(true, root.classList.contains("sidebar-collapsed"), "applies the remembered collapsed state on load");
    assertEquals("false", toggle.getAttribute("aria-expanded"), "reports the collapsed state on the toggle");
    assertEquals("Expand sidebar", toggle.getAttribute("aria-label"), "labels the toggle as expand while collapsed");
}

{
    const { root, toggle, links, storage } = loadSidebarCollapse();
    toggle.click();
    assertEquals(true, root.classList.contains("sidebar-collapsed"), "click collapses the sidebar");
    assertEquals("true", storage.values[STORAGE_KEY], "collapsed state is persisted under the sidebar key");
    assertEquals("Dashboard", links[0].getAttribute("title"), "collapsed links get their menu name as tooltip");
    assertEquals("Room List", links[1].getAttribute("title"), "every collapsed link gets a tooltip");

    toggle.click();
    assertEquals(false, root.classList.contains("sidebar-collapsed"), "second click expands the sidebar");
    assertEquals("false", storage.values[STORAGE_KEY], "expanded state is persisted too");
    assertEquals(null, links[0].getAttribute("title"), "tooltips are removed when expanded");
}

{
    const { root, toggle } = loadSidebarCollapse({ storageBlocked: true });
    assertEquals(false, root.classList.contains("sidebar-collapsed"), "blocked storage falls back to expanded");
    toggle.click();
    assertEquals(true, root.classList.contains("sidebar-collapsed"), "toggle still works when storage is blocked");
}

if (failures > 0) {
    console.log(`\n${failures} assertion(s) failed`);
    process.exit(1);
}
console.log("\nall assertions passed");
