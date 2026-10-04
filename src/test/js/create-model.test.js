"use strict";

/*
 * Regression tests for static/js/reservation/create-model.js (Create Reservation presentation helpers): the derived
 * nights, the informational totals, the soft capacity comparison, the past check-in notice, and the lifecycle of the
 * temporary form state kept across the Create New Guest round trip.
 *
 * Run with: node src/test/js/create-model.test.js
 */

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const SOURCE_PATH = path.join(
    __dirname, "..", "..", "main", "resources", "static", "js", "reservation", "create-model.js");

const sandbox = { window: {} };
vm.runInNewContext(fs.readFileSync(SOURCE_PATH, "utf8"), sandbox, { filename: SOURCE_PATH });
const model = sandbox.window.PmsReservationCreate;

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

/** Minimal sessionStorage stand-in. */
function storage(initial) {
    const items = new Map(Object.entries(initial || {}));
    return {
        items,
        getItem: (key) => (items.has(key) ? items.get(key) : null),
        setItem: (key, value) => items.set(key, String(value)),
        removeItem: (key) => items.delete(key),
    };
}

const GUEST_ID = "33333333-3333-3333-3333-333333333333";
const ROOM_ID = "44444444-4444-4444-4444-444444444444";
const NOW = 1_800_000_000_000;
const defaults = { adults: "1", children: "0" };

function state(overrides) {
    return {
        guest: { id: GUEST_ID, code: "G1", name: "Ann Lee", phone: "", email: "", nationality: "" },
        accompanying: [],
        checkIn: "2026-10-20",
        checkOut: "2026-10-23",
        adults: "2",
        children: "0",
        source: "AGODA",
        ota: "123456789",
        rooms: [{ roomId: ROOM_ID, roomNumber: "101", rate: "1,500,000" }],
        contactName: "",
        contactPhone: "",
        contactEmail: "",
        notes: "late arrival",
        ...overrides,
    };
}

test("nights are the day difference and zero or negative stays have none", () => {
    assert.equal(model.nightsBetween("2026-10-20", "2026-10-23"), 3);
    assert.equal(model.nightsBetween("2026-10-31", "2026-11-01"), 1);
    assert.equal(model.nightsBetween("2026-12-31", "2027-01-02"), 2);
    assert.equal(model.nightsBetween("2026-10-20", "2026-10-20"), null);
    assert.equal(model.nightsBetween("2026-10-23", "2026-10-20"), null);
    assert.equal(model.nightsBetween("", "2026-10-20"), null);
    assert.equal(model.nightsBetween("2026-02-30", "2026-03-02"), null);
});

test("a check-in before the hotel date is flagged, today and later are not", () => {
    assert.equal(model.isPastCheckIn("2026-10-03", "2026-10-04"), true);
    assert.equal(model.isPastCheckIn("2026-10-04", "2026-10-04"), false);
    assert.equal(model.isPastCheckIn("2026-10-05", "2026-10-04"), false);
    assert.equal(model.isPastCheckIn("", "2026-10-04"), false);
});

test("dates display as dd/MM/yyyy", () => {
    assert.equal(model.formatDisplayDate("2026-10-04"), "04/10/2026");
    assert.equal(model.formatDisplayDate(""), "");
});

test("nightly rate accepts whole VND (grouped or with zero decimals) and rejects the rest", () => {
    assert.deepEqual({ ...model.parseRate("1,500,000") }, { status: "ok", value: 1500000 });
    assert.equal(model.parseRate("1500000.00").status, "ok");
    assert.equal(model.parseRate("1500000.5").status, "fraction");
    assert.equal(model.parseRate("0").status, "positive");
    assert.equal(model.parseRate("").status, "empty");
    assert.equal(model.parseRate("12abc").status, "invalid");
    assert.equal(model.parseRate("1.000.000").status, "invalid");
});

test("room total is rate x nights and the estimate is the sum, with no base-price fallback", () => {
    assert.equal(model.roomTotal(1500000, 3), 4500000);
    assert.equal(model.roomTotal(1500000, null), null);
    assert.equal(model.roomTotal(null, 3), null);
    assert.equal(model.estimatedTotal([4500000, 3600000, null]), 8100000);
    assert.equal(model.estimatedTotal([]), 0);
    assert.equal(model.formatMoney(8100000), "8,100,000");
    assert.equal(model.normalizeMoney("1,500,000"), "1500000");
});

test("capacity is compared as a soft warning: ok, exceeded, not configured, or nothing to compare", () => {
    const room = (roomNumber, capacity) => ({ roomNumber, capacity });
    assert.equal(model.assessCapacity(2, [room("101", 2)]).status, "ok");
    const exceeded = model.assessCapacity(5, [room("101", 2), room("102", 2)]);
    assert.equal(exceeded.status, "exceeded");
    assert.equal(exceeded.capacity, 4);
    const unknown = model.assessCapacity(2, [room("101", 2), room("102", null)]);
    assert.equal(unknown.status, "notConfigured");
    assert.deepEqual([...unknown.unconfigured], ["102"]);
    assert.equal(model.assessCapacity(2, []).status, "none");
    assert.equal(model.assessCapacity(NaN, [room("101", 2)]).status, "none");
});

test("notes length counts every line break as CRLF, as the server receives it", () => {
    assert.equal(model.notesLength("a\nb"), 4);
    assert.equal(model.notesLength("a\r\nb"), 4);
    assert.equal(model.NOTES_MAX, 5000);
});

test("saved state is restored once on the Create New Guest return, then gone", () => {
    const store = storage();
    assert.equal(model.saveDraftState(store, state(), NOW), true);
    const restored = model.resolveRestore(store, NOW + 1000, { createdGuestId: "x", navigationType: "navigate" });
    assert.equal(restored.notes, "late arrival");
    assert.equal(restored.rooms[0].roomNumber, "101");
    assert.equal(store.items.size, 0, "the entry is consumed");
    assert.equal(model.resolveRestore(store, NOW + 2000, { createdGuestId: "x" }), null);
});

test("saved state is also restored on Back or when returning from the Guest page, not on an unrelated visit", () => {
    for (const context of [{ navigationType: "back_forward" }, { cameFromGuestCreation: true }]) {
        const store = storage();
        model.saveDraftState(store, state(), NOW);
        assert.notEqual(model.resolveRestore(store, NOW + 1000, context), null);
    }
    const stale = storage();
    model.saveDraftState(stale, state(), NOW);
    assert.equal(model.resolveRestore(stale, NOW + 1000, { navigationType: "navigate" }), null);
    assert.equal(stale.items.size, 0, "a stale entry is discarded by an unrelated visit");
    const reloaded = storage();
    model.saveDraftState(reloaded, state(), NOW);
    assert.equal(model.resolveRestore(reloaded, NOW + 1000, { navigationType: "reload" }), null);
});

test("an expired, future-dated or malformed entry is never restored", () => {
    const expired = storage();
    model.saveDraftState(expired, state(), NOW);
    assert.equal(model.resolveRestore(expired, NOW + model.STATE_TTL_MS + 1, { createdGuestId: "x" }), null);
    assert.equal(expired.items.size, 0);

    const future = storage();
    model.saveDraftState(future, state(), NOW + 10 * 60 * 1000);
    assert.equal(model.resolveRestore(future, NOW, { createdGuestId: "x" }), null);

    const garbage = storage({ [model.STORAGE_KEY]: "{not json" });
    assert.equal(model.resolveRestore(garbage, NOW, { createdGuestId: "x" }), null);
    assert.equal(garbage.items.size, 0);
    const wrongVersion = storage({ [model.STORAGE_KEY]: JSON.stringify({ v: 2, savedAt: NOW }) });
    assert.equal(model.resolveRestore(wrongVersion, NOW, { createdGuestId: "x" }), null);
});

test("restored state is rebuilt from known fields only", () => {
    const clean = model.sanitizeState({
        ...state({ source: "PAYPAL", checkIn: "tomorrow", notes: "n".repeat(20000) }),
        v: 1,
        savedAt: NOW,
        injected: "<script>",
        rooms: [{ roomId: "not-a-uuid", roomNumber: "1", rate: "5", extra: 1 }],
    });
    assert.equal(clean.source, "");
    assert.equal(clean.checkIn, "");
    assert.equal(clean.notes.length <= 7000, true);
    assert.equal(clean.rooms[0].roomId, "");
    assert.equal("injected" in clean, false);
    assert.equal("extra" in clean.rooms[0], false);
});

test("a storage that throws never breaks the page", () => {
    const broken = {
        getItem() { throw new Error("blocked"); },
        setItem() { throw new Error("blocked"); },
        removeItem() { throw new Error("blocked"); },
    };
    assert.equal(model.saveDraftState(broken, state(), NOW), false);
    assert.equal(model.resolveRestore(broken, NOW, { createdGuestId: "x" }), null);
    model.clearDraftState(broken);
});

test("a form with only its defaults is pristine; any meaningful input is not", () => {
    const blank = state({
        guest: null, checkIn: "", checkOut: "", adults: "1", children: "0", source: "", ota: "", notes: "",
        rooms: [{ roomId: "", roomNumber: "", rate: "" }],
    });
    assert.equal(model.isPristine(blank, defaults), true);
    assert.equal(model.isPristine({ ...blank, notes: "x" }, defaults), false);
    assert.equal(model.isPristine({ ...blank, adults: "2" }, defaults), false);
    assert.equal(model.isPristine({ ...blank, rooms: [{ roomId: "", roomNumber: "", rate: "100" }] }, defaults), false);
    assert.equal(model.isPristine({ ...blank, guest: state().guest }, defaults), false);
});

console.log(failures === 0 ? "\nAll create-model.js tests passed." : `\n${failures} create-model.js test(s) FAILED.`);
process.exit(failures === 0 ? 0 : 1);
