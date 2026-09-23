"use strict";

/*
 * Regression tests for static/js/common/money-input.js.
 *
 * The shared money formatter previously stripped the decimal separator on every keystroke, so a typed
 * "20.50" was submitted as "2050" — a 100x financial error that every backend guard accepted because
 * the result was still a valid positive amount. These tests load the real shipped file into a minimal
 * DOM stub and drive it the way staff do: one keystroke at a time through its own "input" listener,
 * then through its own "submit" listener.
 *
 * Run with: node src/test/js/money-input.test.js
 */

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const SOURCE_PATH = path.join(
    __dirname, "..", "..", "main", "resources", "static", "js", "common", "money-input.js");

/** Creates a stub element that records listeners so a test can dispatch real events at it. */
function createElement() {
    return {
        value: "",
        listeners: {},
        addEventListener(type, handler) {
            if (!this.listeners[type]) {
                this.listeners[type] = [];
            }
            this.listeners[type].push(handler);
        },
        dispatch(type) {
            (this.listeners[type] || []).forEach((handler) => handler());
        },
    };
}

/** Loads money-input.js against one money input inside one form, exactly as a page would. */
function loadMoneyInput(initialValue) {
    const input = createElement();
    input.value = initialValue === undefined ? "" : initialValue;

    const form = createElement();
    form.querySelectorAll = (selector) => (selector === ".js-money-input" ? [input] : []);

    const document = {
        readyState: "complete",
        querySelectorAll(selector) {
            if (selector === ".js-money-input") {
                return [input];
            }
            if (selector === "form") {
                return [form];
            }
            return [];
        },
        addEventListener() {},
    };

    vm.runInNewContext(fs.readFileSync(SOURCE_PATH, "utf8"), { document }, { filename: SOURCE_PATH });
    return { input, form };
}

/** Types the characters one at a time, firing the input listener after each, as a browser does. */
function type(input, text) {
    for (const character of text) {
        input.value += character;
        input.dispatch("input");
    }
    return input.value;
}

/** Fires the form submit listener and returns what the browser would send. */
function submit(form, input) {
    form.dispatch("submit");
    return input.value;
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

/** Types a value and asserts both what staff see and what the form submits. */
function assertTyped(text, expectedDisplay, expectedSubmit) {
    const { input, form } = loadMoneyInput();
    const display = type(input, text);
    assertEquals(expectedDisplay, display, `typing "${text}" displays "${expectedDisplay}"`);
    assertEquals(expectedSubmit, submit(form, input), `typing "${text}" submits "${expectedSubmit}"`);
}

console.log("money-input.js");

console.log(" decimal amounts survive incremental typing");
assertTyped("20.50", "20.50", "20.50");
assertTyped("1.5", "1.5", "1.5");
assertTyped("0.5", "0.5", "0.5");
assertTyped("1.05", "1.05", "1.05");
assertTyped("20.5", "20.5", "20.5");

console.log(" whole amounts keep thousands grouping");
assertTyped("1000000", "1,000,000", "1000000");
assertTyped("1000", "1,000", "1000");
assertTyped("100", "100", "100");

console.log(" a decimal exchange rate is grouped and preserved");
assertTyped("25500.50", "25,500.50", "25500.50");

console.log(" the typed numeric value is never changed");
[["20.50", 20.5], ["1.5", 1.5], ["0.5", 0.5], ["1.05", 1.05], ["1000000", 1000000], ["25500.50", 25500.5]]
    .forEach(([text, expected]) => {
        const { input, form } = loadMoneyInput();
        type(input, text);
        assertEquals(expected, Number(submit(form, input)), `"${text}" keeps its numeric value`);
    });

console.log(" ambiguous input is preserved, never silently reinterpreted");
{
    // Pasted Vietnamese-style grouping. It must not become "1"; the backend rejects it instead.
    const { input, form } = loadMoneyInput();
    input.value = "1.000.000";
    input.dispatch("input");
    assertEquals("1.000.000", input.value, "pasted \"1.000.000\" is left as typed");
    assertEquals("1.000.000", submit(form, input), "pasted \"1.000.000\" is submitted unchanged");
}

console.log(" server-rendered values are formatted on load");
assertEquals("1,000,000", loadMoneyInput("1000000").input.value, "\"1000000\" loads as \"1,000,000\"");
assertEquals("20.50", loadMoneyInput("20.50").input.value, "\"20.50\" loads unchanged");
assertEquals("", loadMoneyInput("").input.value, "an empty field stays empty");

console.log(failures === 0 ? "\nAll money-input.js tests passed." : `\n${failures} money-input.js test(s) FAILED.`);
process.exit(failures === 0 ? 0 : 1);
