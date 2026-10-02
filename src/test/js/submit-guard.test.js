"use strict";

/*
 * Regression tests for static/js/common/submit-guard.js (Task33 Batch 1B duplicate-submit guard).
 *
 * Run with: node src/test/js/submit-guard.test.js
 */

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const SOURCE_PATH = path.join(
    __dirname, "..", "..", "main", "resources", "static", "js", "common", "submit-guard.js");

/** Minimal stand-ins for the platform classes, so the script's `instanceof` checks succeed. */
class HTMLButtonElement {}
class HTMLInputElement {}

/** Builds a stub submit button (the kind `event.submitter` would be for a `<button type="submit">`). */
function createButton() {
    const button = Object.create(HTMLButtonElement.prototype);
    const attributes = {};
    button.disabled = false;
    button.dataset = {};
    button.textContent = "Save";
    button.setAttribute = (name, value) => {
        attributes[name] = value;
    };
    button.getAttribute = (name) =>
        (Object.prototype.hasOwnProperty.call(attributes, name) ? attributes[name] : null);
    return button;
}

/** Builds a stub <form data-submit-guard>. */
function createForm() {
    return {
        listeners: {},
        addEventListener(type, handler) {
            (this.listeners[type] = this.listeners[type] || []).push(handler);
        },
        dispatch(type, event) {
            (this.listeners[type] || []).forEach((handler) => handler(event || {}));
        },
    };
}

/** Loads submit-guard.js against the given guarded forms, exactly as a page would. */
function loadSubmitGuard(forms) {
    const documentStub = {
        querySelectorAll: (selector) => (selector === "form[data-submit-guard]" ? forms : []),
    };

    vm.runInNewContext(
        fs.readFileSync(SOURCE_PATH, "utf8"),
        { document: documentStub, HTMLButtonElement, HTMLInputElement },
        { filename: SOURCE_PATH });
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

console.log("submit-guard.js");

console.log(" a plain (unconfirmed) submit disables the submitter and shows its loading text");
{
    const form = createForm();
    const button = createButton();
    button.dataset.loadingText = "Saving…";
    loadSubmitGuard([form]);

    form.dispatch("submit", { submitter: button, defaultPrevented: false });
    assertEquals(true, button.disabled, "the submitter is disabled");
    assertEquals("true", button.getAttribute("aria-busy"), "the submitter is marked aria-busy");
    assertEquals("Saving…", button.textContent, "the submitter text swaps to the loading text");
}

console.log(" a submit left unprevented without a loading-text data attribute still gets disabled");
{
    const form = createForm();
    const button = createButton();
    loadSubmitGuard([form]);

    form.dispatch("submit", { submitter: button, defaultPrevented: false });
    assertEquals(true, button.disabled, "the submitter is disabled even without data-loading-text");
    assertEquals("Save", button.textContent, "the submitter text is left unchanged without data-loading-text");
}

console.log(" a submit already defaultPrevented (confirmation.js's first, unconfirmed click) is left alone");
{
    const form = createForm();
    const button = createButton();
    loadSubmitGuard([form]);

    form.dispatch("submit", { submitter: button, defaultPrevented: true });
    assertEquals(false, button.disabled, "the submitter is not touched while a confirmation dialog is pending");
}

console.log(" a submit without a recognizable submitter button never throws");
{
    const form = createForm();
    loadSubmitGuard([form]);

    form.dispatch("submit", { submitter: null, defaultPrevented: false });
    console.log("  ok   does not throw without a submitter");
}

console.log(" a page with no data-submit-guard forms wires nothing, without throwing");
{
    loadSubmitGuard([]);
    console.log("  ok   does not throw with zero guarded forms");
}

console.log(failures === 0
    ? "\nAll submit-guard.js tests passed."
    : `\n${failures} submit-guard.js test(s) FAILED.`);
process.exit(failures === 0 ? 0 : 1);
