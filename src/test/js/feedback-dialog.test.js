"use strict";

/*
 * Regression tests for static/js/common/feedback-dialog.js (Task33 Batch 1B business/system error
 * dialog).
 *
 * Run with: node src/test/js/feedback-dialog.test.js
 */

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const SOURCE_PATH = path.join(
    __dirname, "..", "..", "main", "resources", "static", "js", "common", "feedback-dialog.js");

/** Minimal stand-in for the platform's HTMLDialogElement, so `instanceof` checks succeed. */
class HTMLDialogElement {}

/** Builds a stub <dialog> with the message/close-button children the script queries for. */
function createDialog(messageText) {
    const dialog = Object.create(HTMLDialogElement.prototype);
    dialog.listeners = {};
    dialog.showModalCount = 0;
    dialog.closeCount = 0;
    dialog.addEventListener = function (type, handler) {
        (this.listeners[type] = this.listeners[type] || []).push(handler);
    };
    dialog.dispatch = function (type, event) {
        (this.listeners[type] || []).forEach((handler) => handler(event || {}));
    };
    dialog.showModal = function () {
        this.showModalCount++;
    };
    dialog.close = function () {
        this.closeCount++;
    };

    const message = { textContent: messageText };
    const closeButton = {
        listeners: {},
        addEventListener(type, handler) {
            (this.listeners[type] = this.listeners[type] || []).push(handler);
        },
        dispatch(type) {
            (this.listeners[type] || []).forEach((handler) => handler());
        },
    };

    dialog.querySelector = (selector) => {
        if (selector === "#feedback-dialog-message") {
            return message;
        }
        if (selector === "[data-feedback-dialog-close]") {
            return closeButton;
        }
        return null;
    };

    return { dialog, message, closeButton };
}

/** Loads feedback-dialog.js against a stub dialog, exactly as a page would. */
function loadFeedbackDialog({ messageText = "", missingDialog = false } = {}) {
    const { dialog, message, closeButton } = createDialog(messageText);
    const documentStub = {
        getElementById: (id) => (id === "feedback-dialog" && !missingDialog ? dialog : null),
    };

    vm.runInNewContext(
        fs.readFileSync(SOURCE_PATH, "utf8"),
        { document: documentStub, HTMLDialogElement },
        { filename: SOURCE_PATH });

    return { dialog, message, closeButton };
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

console.log("feedback-dialog.js");

console.log(" opens the dialog and wires the close button when a message is present");
{
    const { dialog, closeButton } = loadFeedbackDialog({ messageText: "Room is not available." });
    assertEquals(1, dialog.showModalCount, "dialog.showModal() is called once");

    closeButton.dispatch("click");
    assertEquals(1, dialog.closeCount, "the close button closes the dialog");
}

console.log(" a cancel event (Escape) prevents the browser default and closes the dialog itself");
{
    const { dialog } = loadFeedbackDialog({ messageText: "Room is not available." });
    let defaultPrevented = false;
    dialog.dispatch("cancel", { preventDefault: () => { defaultPrevented = true; } });
    assertEquals(true, defaultPrevented, "the native cancel default is prevented");
    assertEquals(1, dialog.closeCount, "cancel closes the dialog through the same close() call");
}

console.log(" does nothing when there is no message to show (whitespace-only content)");
{
    const { dialog } = loadFeedbackDialog({ messageText: "   " });
    assertEquals(0, dialog.showModalCount, "dialog.showModal() is never called for a blank message");
}

console.log(" never throws when the dialog element is missing from the page");
{
    loadFeedbackDialog({ missingDialog: true });
    console.log("  ok   does not throw when #feedback-dialog is absent");
}

console.log(failures === 0
    ? "\nAll feedback-dialog.js tests passed."
    : `\n${failures} feedback-dialog.js test(s) FAILED.`);
process.exit(failures === 0 ? 0 : 1);
