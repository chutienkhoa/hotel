"use strict";

/*
 * Regression tests for static/js/common/numeric-input.js, the one numeric-input behaviour of the PMS
 * (data-numeric="integer" | "vnd" | "decimal"). Letters, signs, extra decimal points and typed commas are refused;
 * a paste is accepted only when it is a clean number for the field; thousands separators are added by the formatter and
 * never reach the submitted value; decimal fields keep taking valid decimals up to their scale.
 *
 * Run with: node src/test/js/numeric-input.test.js
 */

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const SOURCE_PATH = path.join(
    __dirname, "..", "..", "main", "resources", "static", "js", "common", "numeric-input.js");

/** Loads the helper with a minimal document: an optional currency <select> and a recorded set of form listeners. */
function loadHelper(selectsById) {
    const document = {
        readyState: "complete",
        querySelectorAll: () => [],
        querySelector: (selector) => (selectsById || {})[selector] || null,
        addEventListener() {},
    };
    const sandbox = { window: {}, document };
    vm.runInNewContext(fs.readFileSync(SOURCE_PATH, "utf8"), sandbox, { filename: SOURCE_PATH });
    return sandbox.window.PmsNumericInput;
}

/** Stub text input that records listeners and mimics the selection API. */
function createInput(dataset, value) {
    const listeners = {};
    const input = {
        dataset: { ...dataset },
        value: value || "",
        selectionStart: (value || "").length,
        selectionEnd: (value || "").length,
        addEventListener(type, handler) { listeners[type] = handler; },
        setSelectionRange(start, end) { this.selectionStart = start; this.selectionEnd = end; },
    };
    input.fire = (type, event) => listeners[type](event);
    return input;
}

function beforeInput(input, inputType, data) {
    const event = { inputType, data, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
    input.fire("beforeinput", event);
    return event;
}

/** Types one character the way a browser does: beforeinput, then (when allowed) the value change and input event. */
function type(input, character) {
    const event = beforeInput(input, "insertText", character);
    if (event.defaultPrevented) return false;
    const start = input.selectionStart;
    input.value = input.value.slice(0, start) + character + input.value.slice(input.selectionEnd);
    input.selectionStart = input.selectionEnd = start + character.length;
    input.fire("input", {});
    return true;
}

function typeAll(input, text) {
    return [...text].map((character) => type(input, character));
}

function paste(input, text) {
    beforeInput(input, "insertFromPaste", text);
}

let failures = 0;
function test(name, body) {
    try {
        body();
        console.log(` ok   ${name}`);
    } catch (error) {
        failures += 1;
        console.log(` FAIL ${name}\n      ${error.message}`);
    }
}

const helper = loadHelper();
const integer = () => { const input = createInput({ numeric: "integer" }); helper.initialize(input); return input; };
const vnd = (value) => { const input = createInput({ numeric: "vnd" }, value); helper.initialize(input); return input; };
const decimal = (scale, value) => {
    const input = createInput({ numeric: "decimal", numericScale: String(scale) }, value);
    helper.initialize(input);
    return input;
};

// ---------------------------------------------------------------------------------------------------- INTEGER

test("integer: digits are accepted, including leading zeros as typed", () => {
    const input = integer();
    assert.deepEqual(typeAll(input, "123"), [true, true, true]);
    assert.equal(input.value, "123");
    const zeros = integer();
    typeAll(zeros, "001");
    assert.equal(zeros.value, "001");
});

test("integer: letters, signs, decimals and special characters are rejected", () => {
    const input = integer();
    type(input, "1");
    for (const bad of ["a", "-", "+", ".", ",", "e", " ", "$", "٣"]) {
        assert.equal(type(input, bad), false, `"${bad}" must be refused`);
    }
    assert.equal(input.value, "1");
    const mixed = integer();
    typeAll(mixed, "12a");
    assert.equal(mixed.value, "12");
    const decimalAttempt = integer();
    typeAll(decimalAttempt, "1.5");
    assert.equal(decimalAttempt.value, "15");
    const negative = integer();
    typeAll(negative, "-1");
    assert.equal(negative.value, "1");
});

test("integer: a paste is accepted only when it is a clean whole number", () => {
    const ok = integer();
    paste(ok, " 3 ");
    assert.equal(ok.value, "3");
    for (const text of ["1,000", "1.5", "-1", "12a", "1abc000", "", "٣"]) {
        const input = integer();
        type(input, "7");
        paste(input, text);
        assert.equal(input.value, "7", `"${text}" must leave the field alone`);
    }
});

test("integer: anything that still gets in is reduced to digits", () => {
    const input = integer();
    input.value = "-3.5a";
    input.fire("input", {});
    assert.equal(input.value, "35");
});

test("integer: very long input is capped to what a count can hold", () => {
    const input = integer();
    typeAll(input, "12345678901234");
    assert.equal(input.value, "123456789");
});

// ------------------------------------------------------------------------------------------------------ VND

test("vnd: digits show with thousands separators", () => {
    const input = vnd();
    typeAll(input, "1000000");
    assert.equal(input.value, "1,000,000");
    assert.equal(helper.cleanDigits(input.value), "1000000", "the underlying value has no separators");
});

test("vnd: letters never remain ('1000000a', 'abc')", () => {
    const input = vnd("1000000");
    assert.equal(input.value, "1,000,000");
    assert.equal(type(input, "a"), false);
    assert.equal(input.value, "1,000,000");
    const letters = vnd();
    typeAll(letters, "abc");
    assert.equal(letters.value, "", "'abc' must not produce a value");
    const trailing = vnd();
    typeAll(trailing, "1,000,000b");
    assert.equal(trailing.value, "1,000,000");
});

test("vnd: decimals, signs and typed commas are refused", () => {
    const input = vnd();
    typeAll(input, "1000");
    for (const bad of [".", ",", "-", "+", "e", "$"]) assert.equal(type(input, bad), false, `"${bad}"`);
    assert.equal(input.value, "1,000");
});

test("vnd: a clean paste is accepted (grouped or plain), anything else changes nothing", () => {
    const grouped = vnd();
    paste(grouped, "1,500,000");
    assert.equal(grouped.value, "1,500,000");
    const plain = vnd();
    paste(plain, "2500000");
    assert.equal(plain.value, "2,500,000");
    for (const text of ["1abc000", "1,000,000a", "1.5", "-5", "1,00,000", "12,34", "1,000.00", "abc", "1 000"]) {
        const input = vnd("7");
        paste(input, text);
        assert.equal(input.value, "7", `"${text}" must not produce a malformed value`);
    }
});

test("vnd: a drop is treated like a paste and falls back to the transfer data", () => {
    const input = vnd();
    const event = { inputType: "insertFromDrop", data: null, dataTransfer: { getData: () => "4000" },
        defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
    input.fire("beforeinput", event);
    assert.equal(input.value, "4,000");
});

test("vnd: the caret stays after the same digit when separators are added or removed", () => {
    const mode = { grouped: true, scale: 0, maxWhole: 13 };
    assert.deepEqual({ ...helper.render("1000", 1, mode) }, { value: "1,000", caret: 1 });
    assert.deepEqual({ ...helper.render("12345", 5, mode) }, { value: "12,345", caret: 6 });
    assert.deepEqual({ ...helper.render("1,0000", 3, mode) }, { value: "10,000", caret: 2 });
    assert.deepEqual({ ...helper.render("", 0, mode) }, { value: "", caret: 0 });
});

test("vnd: replacing a selection and deleting keep the formatting", () => {
    const input = vnd("1234567");
    input.selectionStart = 0;
    input.selectionEnd = input.value.length;
    typeAll(input, "9");
    assert.equal(input.value, "9", "select-all + replace");
    const deleting = vnd("1000");
    deleting.value = "100";
    deleting.selectionStart = deleting.selectionEnd = 3;
    deleting.fire("input", {});
    assert.equal(deleting.value, "100");
    const grouped = vnd("1000000");
    grouped.value = "1,00,000";
    grouped.selectionStart = grouped.selectionEnd = 4;
    grouped.fire("input", {});
    assert.equal(grouped.value, "100,000", "a backspace through a separator regroups");
});

test("vnd: a server-rendered value that is not a clean whole number is shown as submitted", () => {
    assert.equal(vnd("1500000.5").value, "1500000.5");
    assert.equal(vnd("1500000").value, "1,500,000");
    assert.equal(vnd("1500000.000000").value, "1,500,000");
});

test("vnd: very long input is capped to the stored precision", () => {
    const input = vnd();
    typeAll(input, "12345678901234567");
    assert.equal(helper.cleanDigits(input.value), "1234567890123");
});

// -------------------------------------------------------------------------------------------------- DECIMAL

test("decimal: valid decimals keep working up to the scale (foreign-currency amount, exchange rate)", () => {
    const usd = decimal(2);
    assert.equal(typeAll(usd, "1250.5").every(Boolean), true);
    assert.equal(usd.value, "1,250.5");
    typeAll(usd, "0");
    assert.equal(usd.value, "1,250.50");
    assert.equal(type(usd, "9"), false, "a third fraction digit is refused for scale 2");
    const rate = decimal(6);
    typeAll(rate, "25000.123456");
    assert.equal(rate.value, "25,000.123456");
    assert.equal(type(rate, "7"), false);
});

test("decimal: one decimal point only; letters, signs and typed commas are refused", () => {
    const input = decimal(2);
    typeAll(input, "12.5");
    for (const bad of [".", ",", "-", "+", "a", "e"]) assert.equal(type(input, bad), false, `"${bad}"`);
    assert.equal(input.value, "12.5");
});

test("decimal: a leading point becomes 0. and a trailing point is kept while typing", () => {
    const input = decimal(2);
    type(input, ".");
    assert.equal(input.value, "0.");
    typeAll(input, "5");
    assert.equal(input.value, "0.5");
    const trailing = decimal(2);
    typeAll(trailing, "100.");
    assert.equal(trailing.value, "100.");
});

test("decimal: a scale of 0 behaves as whole numbers", () => {
    const input = decimal(0);
    assert.equal(type(input, "."), false);
    typeAll(input, "1000");
    assert.equal(input.value, "1,000");
});

test("decimal: a clean paste is accepted, a malformed or over-precise one is rejected whole", () => {
    const ok = decimal(2);
    paste(ok, "1,250.75");
    assert.equal(ok.value, "1,250.75");
    for (const text of ["1.234", "1,25.0", "1..5", "1.5.5", "1abc.50", "-1.5", ".5", "5."]) {
        const input = decimal(2, "7");
        paste(input, text);
        assert.equal(input.value, "7", `"${text}" must be rejected`);
    }
});

test("decimal: server-rendered values drop insignificant trailing zeros and keep the rest", () => {
    assert.equal(decimal(6, "25000.000000").value, "25,000");
    assert.equal(decimal(6, "25000.500000").value, "25,000.5");
    assert.equal(decimal(2, "1500000.50").value, "1,500,000.5");
    assert.equal(decimal(2, "1.234").value, "1.234", "more precision than the scale is shown as submitted");
});

test("decimal: the scale follows a currency <select> (VND 0, USD 2) and tracks its changes", () => {
    const select = { addEventListener() {}, selectedIndex: 0, options: [{ dataset: { fractionDigits: "0" } }, { dataset: { fractionDigits: "2" } }] };
    const loaded = loadHelper({ "#cur": select });
    const input = createInput({ numeric: "decimal", numericScaleFrom: "#cur" });
    loaded.initialize(input);
    assert.equal(type(input, "."), false, "VND takes no fraction");
    select.selectedIndex = 1;
    typeAll(input, "5.25");
    assert.equal(input.value, "5.25", "USD takes two fraction digits");
    assert.equal(type(input, "1"), false);
});

// -------------------------------------------------------------------------------------------------------- misc

test("clean-number detection per mode", () => {
    const money = { grouped: true, scale: 0, maxWhole: 13 };
    const usd = { grouped: true, scale: 2, maxWhole: 13 };
    const count = { grouped: false, scale: 0, maxWhole: 9 };
    assert.equal(helper.isCleanNumber("1,000", money), true);
    assert.equal(helper.isCleanNumber("1,00", money), false);
    assert.equal(helper.isCleanNumber("10.0", money), false);
    assert.equal(helper.isCleanNumber("10.25", usd), true);
    assert.equal(helper.isCleanNumber("10.256", usd), false);
    assert.equal(helper.isCleanNumber("1,000", count), false);
    assert.equal(helper.isCleanNumber("12345678901234", money), false);
});

console.log(failures === 0 ? "\nAll numeric-input.js tests passed." : `\n${failures} numeric-input.js test(s) FAILED.`);
process.exit(failures === 0 ? 0 : 1);
