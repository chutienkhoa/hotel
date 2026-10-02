"use strict";

/*
 * Regression tests for static/js/common/header-account.js (Task33 Batch 1A header account dropdown).
 *
 * Run with: node src/test/js/header-account.test.js
 */

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const SOURCE_PATH = path.join(
    __dirname, "..", "..", "main", "resources", "static", "js", "common", "header-account.js");

/** Creates a stub DOM node that records listeners and attributes, and can report containment. */
function createNode() {
    const attributes = {};
    return {
        hidden: true,
        focusCount: 0,
        listeners: {},
        containsTargets: new Set(),
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
        focus() {
            this.focusCount++;
        },
        contains(target) {
            return target === this || this.containsTargets.has(target);
        },
    };
}

/** Loads header-account.js against stub trigger/menu elements, exactly as a page would. */
function loadHeaderAccount({ missing } = {}) {
    const trigger = createNode();
    trigger.setAttribute("aria-expanded", "false");
    const menu = createNode();
    const documentStub = createNode();
    documentStub.getElementById = (id) => {
        if (missing === id) {
            return null;
        }
        return { "header-account-trigger": trigger, "header-account-menu": menu }[id] || null;
    };

    vm.runInNewContext(
        fs.readFileSync(SOURCE_PATH, "utf8"), { document: documentStub }, { filename: SOURCE_PATH });
    return { trigger, menu, documentStub };
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

console.log("header-account.js");

console.log(" clicking the trigger opens the menu");
{
    const { trigger, menu } = loadHeaderAccount();
    trigger.dispatch("click");
    assertEquals("true", trigger.getAttribute("aria-expanded"), "trigger aria-expanded becomes true");
    assertEquals(false, menu.hidden, "menu is un-hidden");
}

console.log(" clicking the trigger again closes the menu");
{
    const { trigger, menu } = loadHeaderAccount();
    trigger.dispatch("click");
    trigger.dispatch("click");
    assertEquals("false", trigger.getAttribute("aria-expanded"), "trigger aria-expanded returns to false");
    assertEquals(true, menu.hidden, "menu is hidden again");
}

console.log(" clicking outside the open menu closes it, but a click inside the menu does not");
{
    const { trigger, menu, documentStub } = loadHeaderAccount();
    trigger.dispatch("click");

    const insideMenuTarget = {};
    menu.containsTargets.add(insideMenuTarget);
    documentStub.dispatch("click", { target: insideMenuTarget });
    assertEquals(false, menu.hidden, "a click inside the menu does not close it");

    const outsideTarget = {};
    documentStub.dispatch("click", { target: outsideTarget });
    assertEquals(true, menu.hidden, "a click outside the menu closes it");
    assertEquals("false", trigger.getAttribute("aria-expanded"), "trigger aria-expanded follows the outside click");
}

console.log(" an outside click while already closed does nothing");
{
    const { menu, documentStub } = loadHeaderAccount();
    documentStub.dispatch("click", { target: {} });
    assertEquals(true, menu.hidden, "menu stays hidden");
}

console.log(" Escape closes an open menu and returns focus to the trigger, but does nothing when already closed");
{
    const { trigger, menu, documentStub } = loadHeaderAccount();
    documentStub.dispatch("keydown", { key: "Escape" });
    assertEquals(0, trigger.focusCount, "Escape does not steal focus while already closed");

    trigger.dispatch("click");
    documentStub.dispatch("keydown", { key: "Escape" });
    assertEquals(true, menu.hidden, "Escape closes the open menu");
    assertEquals("false", trigger.getAttribute("aria-expanded"), "Escape resets trigger aria-expanded");
    assertEquals(1, trigger.focusCount, "Escape returns focus to the trigger button");
}

console.log(" loading against a page missing the trigger or the menu never throws");
["header-account-trigger", "header-account-menu"].forEach((missingId) => {
    loadHeaderAccount({ missing: missingId });
    console.log(`  ok   does not throw when #${missingId} is absent`);
});

console.log(failures === 0 ? "\nAll header-account.js tests passed." : `\n${failures} header-account.js test(s) FAILED.`);
process.exit(failures === 0 ? 0 : 1);
