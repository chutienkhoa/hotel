"use strict";

/*
 * Regression tests for the Folio Payments table: typed column sorting (static/js/stay/folio-charges.js) and
 * Payment-id-keyed row selection (static/js/stay/folio.js) that must survive re-sorting.
 *
 * Run with: node src/test/js/folio-payments-sort.test.js
 */

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const STATIC_JS = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "stay");
const FOLIO_JS = fs.readFileSync(path.join(STATIC_JS, "folio.js"), "utf8");
const SORT_JS = fs.readFileSync(path.join(STATIC_JS, "folio-charges.js"), "utf8");

let failures = 0;
function assertEquals(expected, actual, message) {
    if (JSON.stringify(expected) !== JSON.stringify(actual)) {
        failures += 1;
        console.error(`  FAIL: ${message}\n    expected: ${JSON.stringify(expected)}\n    actual:   ${JSON.stringify(actual)}`);
    } else {
        console.log(`  ok: ${message}`);
    }
}

function createElement(dataset = {}) {
    const classes = new Set();
    const attributes = {};
    return {
        dataset, hidden: false, textContent: "", listeners: {},
        classList: {
            toggle(name, force) {
                const on = force === undefined ? !classes.has(name) : force;
                if (on) classes.add(name); else classes.delete(name);
                return on;
            },
            contains: (name) => classes.has(name),
        },
        toggleAttribute(name, force) {
            const on = force === undefined ? !(name in attributes) : force;
            if (on) attributes[name] = ""; else delete attributes[name];
            return on;
        },
        setAttribute(name, value) { attributes[name] = String(value); },
        getAttribute(name) { return name in attributes ? attributes[name] : null; },
        addEventListener(type, handler) { (this.listeners[type] = this.listeners[type] || []).push(handler); },
        dispatch(type) { (this.listeners[type] || []).forEach((handler) => handler({ key: "", preventDefault() {} })); },
    };
}

const PAYMENTS = [
    { id: "p1", date: 1789257600000, method: "CASH", reference: "", currency: "VND", amount: "900000", applied: "900000", status: "PAID", added: "admin" },
    { id: "p2", date: 1789084800000, method: "BANK_TRANSFER", reference: "TX-2", currency: "USD", amount: "120", applied: "3000000", status: "REFUNDED", added: "front-desk" },
    { id: "p3", date: null, method: "OTA", reference: "TX-10", currency: "VND", amount: "1500000", applied: "1500000", status: "PENDING", added: "" },
];

const built = PAYMENTS.map((p) => ({
    row: createElement({
        paymentRow: p.id, sortDate: p.date === null ? "" : String(p.date), sortMethod: p.method, sortReference: p.reference,
        sortCurrency: p.currency, sortAmount: p.amount, sortApplied: p.applied, sortStatus: p.status, sortAdded: p.added,
    }),
    detail: Object.assign(createElement({ paymentDetail: p.id }), { hidden: true }),
}));
const rows = built.map((b) => b.row);
const details = built.map((b) => b.detail);
const tbody = {
    get rows() { return rows; },
    appendChild(row) { rows.splice(rows.indexOf(row), 1); rows.push(row); },
};
const keys = ["date", "method", "reference", "currency", "amount", "applied", "status", "added"];
const buttons = keys.map((key) => {
    const icon = createElement();
    const th = createElement();
    const button = createElement({ sortKey: key });
    button.querySelector = () => icon;
    button.closest = () => th;
    return { button, th, icon };
});
const table = {
    tBodies: [tbody],
    querySelectorAll: (selector) => (selector === "[data-sort-key]" ? buttons.map((b) => b.button) : []),
};
const lists = { "[data-payment-row]": rows, "[data-payment-detail]": details };
const document = {
    querySelector: (selector) => (selector.includes("table.folio-table--payments") ? table : null),
    querySelectorAll: (selector) => lists[selector] || [],
    getElementById: () => null,
};
const context = vm.createContext({ document });
vm.runInContext(FOLIO_JS, context);
vm.runInContext(SORT_JS, context);

const order = () => rows.map((row) => row.dataset.paymentRow);
const click = (key) => buttons.find((b) => b.button.dataset.sortKey === key).button.dispatch("click");

console.log("Default sort is Date ascending, missing dates last");
assertEquals(["p2", "p1", "p3"], order(), "oldest first, undated last");

console.log("Typed sorting");
click("amount");
assertEquals(["p2", "p1", "p3"], order(), "amount ascending is numeric (120 < 900000 < 1500000)");
click("amount");
assertEquals(["p3", "p1", "p2"], order(), "amount descending is numeric");
click("reference");
assertEquals(["p3", "p2", "p1"], order(), "reference sorts as text (TX-10 before TX-2), empty reference last");
click("currency");
assertEquals("ascending", buttons.find((b) => b.button.dataset.sortKey === "currency").th.getAttribute("aria-sort"), "currency becomes the ascending column");

console.log("Selection is keyed by Payment id");
rows.find((r) => r.dataset.paymentRow === "p2").dispatch("click");
click("status");
click("status");
assertEquals(true, rows.find((r) => r.dataset.paymentRow === "p2").classList.contains("folio-charge-row--selected"), "p2 stays highlighted after re-sorting");
assertEquals(["p2"], details.filter((d) => !d.hidden).map((d) => d.dataset.paymentDetail), "Payment Detail shows only p2");

console.log(failures === 0 ? "\nAll folio-payments sort tests passed." : `\n${failures} folio-payments sort test(s) FAILED.`);
process.exit(failures === 0 ? 0 : 1);
