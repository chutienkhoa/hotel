"use strict";

/*
 * Regression tests for the Folio Charges table: column sorting (static/js/stay/folio-charges.js) and the
 * row-to-Charge Detail selection that must keep working after rows are reordered (static/js/stay/folio.js).
 *
 * The real shipped files are loaded into a minimal DOM stub in the same order the template includes them, and
 * driven the way staff do: clicking headers and rows. Sort values come from the data-sort-* attributes, so the
 * fixtures below mirror what the server renders (empty attribute for a missing value).
 *
 * Run with: node src/test/js/folio-charges-sort.test.js
 */

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const STATIC_JS = path.join(__dirname, "..", "..", "main", "resources", "static", "js", "stay");
const FOLIO_JS = fs.readFileSync(path.join(STATIC_JS, "folio.js"), "utf8");
const FOLIO_CHARGES_JS = fs.readFileSync(path.join(STATIC_JS, "folio-charges.js"), "utf8");

let failures = 0;

function assertEquals(expected, actual, message) {
    const ok = JSON.stringify(expected) === JSON.stringify(actual);
    if (!ok) {
        failures += 1;
        console.error(`  FAIL: ${message}\n    expected: ${JSON.stringify(expected)}\n    actual:   ${JSON.stringify(actual)}`);
    } else {
        console.log(`  ok: ${message}`);
    }
}

/** A minimal element stub: classes, attributes, hidden state and recorded event listeners. */
function createElement(dataset = {}) {
    const classes = new Set();
    const attributes = {};
    return {
        dataset,
        hidden: false,
        textContent: "",
        listeners: {},
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
        addEventListener(type, handler) {
            (this.listeners[type] = this.listeners[type] || []).push(handler);
        },
        dispatch(type) {
            (this.listeners[type] || []).forEach((handler) => handler({ key: "", preventDefault() {} }));
        },
    };
}

/** Builds one Charge's row and Charge Detail panel with the same data attributes the template renders. */
function createCharge(charge) {
    const row = createElement({
        chargeRow: charge.id,
        sortDate: charge.date === null ? "" : String(charge.date),
        sortType: charge.type,
        sortQty: charge.qty ?? "",
        sortPrice: charge.price ?? "",
        sortAmount: charge.amount,
        sortStatus: charge.status,
        sortAdded: charge.added ?? "",
    });
    const detail = createElement({ chargeDetail: charge.id });
    detail.hidden = true;
    return { row, detail };
}

const CHARGES = [
    // Server order is deliberately not the default sort order, so the initial sort is observable.
    { id: "c4", date: 1789257600000, type: "LAUNDRY", qty: "2", price: null, amount: "50000", status: "ACTIVE", added: "Zed" },
    { id: "c2", date: 1789084800000, type: "BREAKFAST", qty: "10", price: "9", amount: "90", status: "VOIDED", added: "admin" },
    { id: "c1", date: 1788825600000, type: "ROOM", qty: "1", price: "1000000", amount: "1000000", status: "ACTIVE", added: "front-desk" },
    { id: "c3", date: null, type: "SERVICE", qty: null, price: "200000", amount: "200000", status: "ACTIVE", added: null },
];

/** Loads folio.js then folio-charges.js against one shared DOM stub, exactly as the template includes them. */
function loadFolioCharges() {
    const built = CHARGES.map(createCharge);
    const rows = built.map((item) => item.row);
    const details = built.map((item) => item.detail);

    const tbody = {
        get rows() { return rows; },
        appendChild(row) {
            rows.splice(rows.indexOf(row), 1);
            rows.push(row);
        },
    };

    const buttons = ["date", "type", "qty", "price", "amount", "status", "added"].map((key) => {
        const icon = createElement();
        const th = createElement();
        const button = createElement({ sortKey: key });
        button.querySelector = () => icon;
        button.closest = () => th;
        icon.textContent = "";
        return { button, th, icon };
    });

    const table = {
        tBodies: [tbody],
        querySelectorAll(selector) {
            return selector === "[data-sort-key]" ? buttons.map((item) => item.button) : [];
        },
    };

    const lists = { "[data-charge-row]": rows, "[data-charge-detail]": details };
    const document = {
        querySelector(selector) {
            return selector.startsWith("table.folio-table--charges") ? table : null;
        },
        querySelectorAll(selector) {
            return lists[selector] || [];
        },
        getElementById() {
            return null;
        },
    };

    const context = vm.createContext({ document });
    vm.runInContext(FOLIO_JS, context);
    vm.runInContext(FOLIO_CHARGES_JS, context);

    const header = (key) => buttons.find((item) => item.button.dataset.sortKey === key);
    return {
        rows,
        details,
        header,
        orderIds: () => rows.map((row) => row.dataset.chargeRow),
        click: (key) => header(key).button.dispatch("click"),
        clickRow: (id) => rows.find((row) => row.dataset.chargeRow === id).dispatch("click"),
    };
}

console.log("Default sort");
{
    const folio = loadFolioCharges();
    assertEquals(["c1", "c2", "c4", "c3"], folio.orderIds(), "DATE ascending on load, missing date last");
    assertEquals("ascending", folio.header("date").th.getAttribute("aria-sort"), "DATE header reports aria-sort ascending");
    assertEquals("↑", folio.header("date").icon.textContent, "DATE shows the ascending arrow");
    assertEquals("none", folio.header("type").th.getAttribute("aria-sort"), "other headers report aria-sort none");
    assertEquals("↕", folio.header("type").icon.textContent, "inactive headers show the neutral idle icon");
}

console.log("Each column: first click ascending, second click descending");
{
    const expectations = {
        type: [["c2", "c4", "c1", "c3"], ["c3", "c1", "c4", "c2"]],
        qty: [["c1", "c4", "c2", "c3"], ["c2", "c4", "c1", "c3"]],
        price: [["c2", "c3", "c1", "c4"], ["c1", "c3", "c2", "c4"]],
        amount: [["c2", "c4", "c3", "c1"], ["c1", "c3", "c4", "c2"]],
        // Status ties keep their current relative order (the sort is stable), so ACTIVE rows stay in DATE order.
        status: [["c1", "c4", "c3", "c2"], ["c2", "c1", "c4", "c3"]],
        added: [["c2", "c1", "c4", "c3"], ["c4", "c1", "c2", "c3"]],
    };
    Object.entries(expectations).forEach(([key, [ascending, descending]]) => {
        const folio = loadFolioCharges();
        folio.click(key);
        assertEquals(ascending, folio.orderIds(), `${key} first click sorts ascending`);
        assertEquals("ascending", folio.header(key).th.getAttribute("aria-sort"), `${key} header reports ascending`);
        assertEquals("↑", folio.header(key).icon.textContent, `${key} shows the ascending arrow`);
        folio.click(key);
        assertEquals(descending, folio.orderIds(), `${key} second click sorts descending, missing value still last`);
        assertEquals("descending", folio.header(key).th.getAttribute("aria-sort"), `${key} header reports descending`);
        assertEquals("↓", folio.header(key).icon.textContent, `${key} shows the descending arrow`);
    });
}

console.log("Numeric sorting uses values, not formatted or string order");
{
    const folio = loadFolioCharges();
    folio.click("qty");
    assertEquals(["c1", "c4", "c2", "c3"], folio.orderIds(), "QTY 1, 2, 10 (string order would put 10 before 2)");
    assertEquals("2", folio.rows[1].dataset.sortQty, "sorting leaves the row's qty value unchanged");
}

console.log("Only one column is active at a time");
{
    const folio = loadFolioCharges();
    folio.click("type");
    assertEquals("none", folio.header("date").th.getAttribute("aria-sort"), "DATE returns to unsorted when TYPE is clicked");
    assertEquals("↕", folio.header("date").icon.textContent, "DATE returns to the idle icon");
    assertEquals("ascending", folio.header("type").th.getAttribute("aria-sort"), "TYPE becomes the ascending column");
    folio.click("type");
    folio.click("amount");
    assertEquals("none", folio.header("type").th.getAttribute("aria-sort"), "TYPE returns to unsorted after AMOUNT is clicked");
}

console.log("Row selection stays keyed by Charge id after sorting");
{
    const folio = loadFolioCharges();
    folio.click("amount");
    folio.clickRow("c1");
    const selected = folio.rows.filter((row) => row.classList.contains("folio-charge-row--selected"));
    assertEquals(["c1"], selected.map((row) => row.dataset.chargeRow), "clicking a sorted row highlights only that row");
    assertEquals(true, folio.rows.find((row) => row.dataset.chargeRow === "c1").getAttribute("aria-current") !== null,
        "the selected row carries aria-current");
    assertEquals(
        ["c1"],
        folio.details.filter((panel) => !panel.hidden).map((panel) => panel.dataset.chargeDetail),
        "Charge Detail shows the selected Charge's panel only",
    );

    folio.click("qty");
    assertEquals(
        ["c1"],
        folio.details.filter((panel) => !panel.hidden).map((panel) => panel.dataset.chargeDetail),
        "re-sorting keeps the same Charge selected and its detail shown",
    );

    folio.clickRow("c2");
    assertEquals(
        ["c2"],
        folio.details.filter((panel) => !panel.hidden).map((panel) => panel.dataset.chargeDetail),
        "after re-sorting, selecting another row shows that Charge's detail",
    );
    assertEquals(
        ["c2"],
        folio.rows.filter((row) => row.classList.contains("folio-charge-row--selected")).map((row) => row.dataset.chargeRow),
        "only the newly selected row stays highlighted",
    );
}

console.log(failures === 0 ? "\nAll folio-charges sort tests passed." : `\n${failures} folio-charges sort test(s) FAILED.`);
process.exit(failures === 0 ? 0 : 1);
