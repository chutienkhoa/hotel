"use strict";

/*
 * Regression tests for static/js/common/toast.js (Task33 Batch 1B global success/warning toast).
 *
 * Run with: node src/test/js/toast.test.js
 */

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const SOURCE_PATH = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "common", "toast.js");

/** Creates a stub element supporting the handful of DOM operations toast.js actually uses. */
function createElementNode() {
    const attributes = {};
    return {
        dataset: {},
        children: [],
        listeners: {},
        removed: false,
        className: "",
        textContent: "",
        setAttribute(name, value) {
            attributes[name] = value;
        },
        getAttribute(name) {
            return Object.prototype.hasOwnProperty.call(attributes, name) ? attributes[name] : null;
        },
        addEventListener(type, handler) {
            (this.listeners[type] = this.listeners[type] || []).push(handler);
        },
        dispatch(type) {
            (this.listeners[type] || []).forEach((handler) => handler());
        },
        append(...nodes) {
            this.children.push(...nodes);
        },
        remove() {
            this.removed = true;
        },
        querySelector(selector) {
            if (selector === "[data-toast-close]") {
                return this.children.find((child) => child.isCloseButton) || null;
            }
            return null;
        },
    };
}

/** Builds a server-rendered initial toast (as the Thymeleaf fragment would emit it). */
function createInitialToast({ autoDismissMs } = {}) {
    const toast = createElementNode();
    const closeButton = createElementNode();
    closeButton.isCloseButton = true;
    toast.children.push(closeButton);
    if (autoDismissMs) {
        toast.dataset.toastAutoDismiss = String(autoDismissMs);
    }
    return { toast, closeButton };
}

/** Builds a stub #toast-region holding the given initial toasts. */
function createRegion(initialToasts) {
    return {
        toasts: initialToasts.slice(),
        appendChild(node) {
            this.toasts.push(node);
        },
        querySelectorAll(selector) {
            return selector === "[data-toast]" ? this.toasts.slice() : [];
        },
    };
}

/** Loads toast.js against a stub #toast-region, exactly as a page would. */
function loadToast({ initialToasts = [], missingRegion = false } = {}) {
    const region = createRegion(initialToasts);
    const scheduled = [];
    const windowStub = {};
    const documentStub = {
        getElementById: (id) => (id === "toast-region" && !missingRegion ? region : null),
        createElement: () => createElementNode(),
    };

    vm.runInNewContext(
        fs.readFileSync(SOURCE_PATH, "utf8"),
        {
            document: documentStub,
            window: windowStub,
            setTimeout: (callback, delay) => {
                scheduled.push({ callback, delay });
                return scheduled.length;
            },
        },
        { filename: SOURCE_PATH });

    return { region, window: windowStub, scheduled };
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

console.log("toast.js");

console.log(" a server-rendered success toast schedules auto-dismiss and dismisses when it fires");
{
    const { toast } = createInitialToast({ autoDismissMs: 6000 });
    const { scheduled } = loadToast({ initialToasts: [toast] });
    assertEquals(1, scheduled.length, "one auto-dismiss timer is scheduled");
    assertEquals(6000, scheduled[0].delay, "the timer uses the server-rendered delay");
    assertEquals(false, toast.removed, "the toast is not removed before the timer fires");

    scheduled[0].callback();
    assertEquals(true, toast.removed, "the toast is removed once the timer fires");
}

console.log(" clicking a toast's close button removes it immediately");
{
    const { toast, closeButton } = createInitialToast({ autoDismissMs: 6000 });
    loadToast({ initialToasts: [toast] });

    closeButton.dispatch("click");
    assertEquals(true, toast.removed, "the toast is removed on close click");
}

console.log(" a server-rendered warning toast (no auto-dismiss attribute) is never auto-scheduled");
{
    const { toast, closeButton } = createInitialToast();
    const { scheduled } = loadToast({ initialToasts: [toast] });
    assertEquals(0, scheduled.length, "no auto-dismiss timer is scheduled for a warning toast");

    closeButton.dispatch("click");
    assertEquals(true, toast.removed, "a warning toast can still be dismissed manually");
}

console.log(" PmsToast.show('message', 'success') appends an auto-dismissing toast to the region");
{
    const { region, window, scheduled } = loadToast({});
    window.PmsToast.show("Saved successfully.", "success");

    assertEquals(1, region.toasts.length, "one toast is appended to the region");
    assertEquals("toast toast--success", region.toasts[0].className, "the success variant class is applied");
    assertEquals(1, scheduled.length, "the new success toast schedules its own auto-dismiss");

    scheduled[0].callback();
    assertEquals(true, region.toasts[0].removed, "the dynamically created toast is removed once its timer fires");
}

console.log(" PmsToast.show('message', 'warning') never auto-dismisses");
{
    const { window, scheduled } = loadToast({});
    window.PmsToast.show("Outstanding balance remains.", "warning");
    assertEquals(0, scheduled.length, "no auto-dismiss timer is scheduled for a dynamically created warning toast");
}

console.log(" never throws when #toast-region is missing from the page");
{
    const { window } = loadToast({ missingRegion: true });
    assertEquals(undefined, window.PmsToast, "PmsToast is never exposed without a toast region to render into");
}

console.log(failures === 0 ? "\nAll toast.js tests passed." : `\n${failures} toast.js test(s) FAILED.`);
process.exit(failures === 0 ? 0 : 1);
