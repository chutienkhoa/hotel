"use strict";

/*
 * Regression tests for static/js/common/number-stepper.js, the one Number Stepper of the PMS
 * ([ - ] [ editable whole number ] [ + ] for quantity / count fields, opted into with data-stepper).
 *
 * Run with: node src/test/js/number-stepper.test.js
 */

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const STATIC_JS = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "common");

/** Minimal element: attributes, classes, children and recorded listeners / dispatched events. */
function createElement(extra) {
    const attributes = {};
    const listeners = {};
    const element = {
        attributes,
        listeners,
        children: [],
        classes: new Set(),
        dataset: {},
        className: "",
        innerHTML: "",
        disabled: false,
        readOnly: false,
        value: "",
        id: "",
        dispatched: [],
        classList: { add(name) { element.classes.add(name); } },
        setAttribute(name, value) { attributes[name] = value; },
        addEventListener(type, handler) { (listeners[type] = listeners[type] || []).push(handler); },
        dispatchEvent(event) { element.dispatched.push(event.type); return true; },
        append(...nodes) { element.children.push(...nodes); },
        insertBefore() {},
        click() { (listeners.click || []).forEach((handler) => handler({})); },
        press(key) {
            const event = { key, prevented: false, preventDefault() { this.prevented = true; } };
            (listeners.keydown || []).forEach((handler) => handler(event));
            return event;
        },
        ...extra,
    };
    return element;
}

/** Loads number-stepper.js (and numeric-input.js, as the pages do) against a stub document; returns the helper. */
function load() {
    const document = {
        readyState: "complete",
        createElement: () => createElement(),
        querySelector: (selector) => (selector.startsWith("label[for") ? { textContent: "Adults *" } : null),
        querySelectorAll: () => [],
        addEventListener() {},
    };
    const sandbox = { window: {}, document, Event: class { constructor(type) { this.type = type; } } };
    sandbox.window.PmsI18n = { t: (key, fallback) => fallback };
    for (const file of ["numeric-input.js", "number-stepper.js"]) {
        const source = path.join(STATIC_JS, file);
        vm.runInNewContext(fs.readFileSync(source, "utf8"), sandbox, { filename: source });
    }
    return sandbox.window.PmsNumberStepper;
}

function field(value, dataset, flags) {
    const parent = createElement();
    return createElement({
        id: "adultCount",
        value,
        parentNode: parent,
        dataset: { numeric: "integer", stepper: "", ...dataset },
        ...flags,
    });
}

const stepper = load();
const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test("plus adds one and minus subtracts one", () => {
    const input = field("2", { stepperMin: "1" });
    assert.equal(stepper.nextValue(input, 1), 3);
    assert.equal(stepper.nextValue(input, -1), 1);
});

test("minus does nothing at the configured minimum (Adults 1, Children 0)", () => {
    assert.equal(stepper.nextValue(field("1", { stepperMin: "1" }), -1), null);
    assert.equal(stepper.nextValue(field("0", { stepperMin: "0" }), -1), null);
    assert.equal(stepper.nextValue(field("1", { stepperMin: "0" }), -1), 0);
});

test("no maximum is imposed unless data-stepper-max is configured", () => {
    assert.equal(stepper.nextValue(field("20", { stepperMin: "1" }), 1), 21);
    assert.equal(stepper.nextValue(field("99", { stepperMin: "1" }), 1), 100);
    assert.equal(stepper.nextValue(field("5", { stepperMin: "1", stepperMax: "5" }), 1), null);
});

test("an empty or non-numeric field steps up to the minimum and cannot step down", () => {
    assert.equal(stepper.nextValue(field("", { stepperMin: "1" }), 1), 1);
    assert.equal(stepper.nextValue(field("", { stepperMin: "0" }), 1), 0);
    assert.equal(stepper.nextValue(field("abc", { stepperMin: "1" }), 1), 1);
    assert.equal(stepper.nextValue(field("", { stepperMin: "1" }), -1), null);
});

test("step is configurable and defaults to 1", () => {
    assert.equal(stepper.nextValue(field("2", { stepperMin: "0", stepperStep: "5" }), 1), 7);
    assert.equal(stepper.nextValue(field("2", { stepperMin: "0", stepperStep: "5" }), -1), 0);
});

test("a value too large for the field's whole-number limit is not offered", () => {
    assert.equal(stepper.nextValue(field("999999999", { stepperMin: "1" }), 1), null);
});

test("initialize wraps the input with a minus and a plus button that keep the input editable", () => {
    const input = field("2", { stepperMin: "1" });
    stepper.initialize(input);
    assert.ok(input.classes.has("number-stepper__input"));
    assert.equal(input.disabled, false);
    assert.equal(input.readOnly, false);
});

test("clicking the buttons changes the value and fires input and change; ArrowUp / ArrowDown step too", () => {
    const input = field("2", { stepperMin: "1" });
    let wrapper = null;
    input.parentNode.insertBefore = (node) => { wrapper = node; };
    stepper.initialize(input);
    const [minus, , plus] = wrapper.children;
    assert.equal(wrapper.className, "number-stepper");
    assert.equal(wrapper.children[1], input);
    assert.equal(minus.attributes["aria-label"], "Decrease Adults");
    assert.equal(plus.attributes["aria-label"], "Increase Adults");
    plus.click();
    assert.equal(input.value, "3");
    assert.deepEqual(input.dispatched, ["input", "change"]);
    minus.click();
    minus.click();
    assert.equal(input.value, "1");
    assert.equal(minus.disabled, true, "minus is disabled at the minimum");
    minus.click();
    assert.equal(input.value, "1");
    const event = input.press("ArrowUp");
    assert.equal(event.prevented, true);
    assert.equal(input.value, "2");
    input.press("ArrowDown");
    assert.equal(input.value, "1");
});

test("a disabled or read-only field is never changed", () => {
    const disabled = field("2", { stepperMin: "1" }, { disabled: true });
    stepper.initialize(disabled);
    disabled.press("ArrowUp");
    assert.equal(disabled.value, "2");
    const readOnly = field("2", { stepperMin: "1" }, { readOnly: true });
    stepper.initialize(readOnly);
    readOnly.press("ArrowUp");
    assert.equal(readOnly.value, "2");
});

let failed = 0;
for (const [name, fn] of tests) {
    try {
        fn();
        console.log("ok   - " + name);
    } catch (error) {
        failed += 1;
        console.log("FAIL - " + name + "\n" + error.stack);
    }
}
if (failed) {
    process.exit(1);
}
console.log(tests.length + " tests passed");
