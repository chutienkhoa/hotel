(function (root) {
    "use strict";

    // Presentation helpers for the Create Reservation page (static/js/reservation/create.js): date and money
    // formatting, the informational totals and capacity comparison, and the lifecycle of the temporary form state kept
    // across the Create New Guest round trip. Nothing here decides a business outcome: the server validates and
    // creates the DRAFT, and the saved state is only unsaved form input, never authoritative.

    const STORAGE_KEY = "pms.reservationCreate.draft.v1";
    // The state only has to survive a visit to the Guest creation page; anything older is stale.
    const STATE_TTL_MS = 30 * 60 * 1000;
    const NOTES_MAX = 5000;
    const SOURCES = ["DIRECT", "AGODA", "BOOKING_COM", "AIRBNB"];
    const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    const ISO_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;
    const DAY_MS = 86400000;

    /** Parses an ISO date (yyyy-MM-dd) to a UTC day number, or null when it is not a real calendar date. */
    const parseIsoDate = (iso) => {
        const match = ISO_DATE_PATTERN.exec(iso || "");
        if (!match) return null;
        const year = Number(match[1]);
        const month = Number(match[2]);
        const day = Number(match[3]);
        const time = Date.UTC(year, month - 1, day);
        const check = new Date(time);
        if (check.getUTCFullYear() !== year || check.getUTCMonth() !== month - 1 || check.getUTCDate() !== day) {
            return null;
        }
        return time;
    };

    /** Whole nights between two ISO dates (the same arithmetic as DAYS.between), or null unless check-out is later. */
    const nightsBetween = (checkInIso, checkOutIso) => {
        const from = parseIsoDate(checkInIso);
        const to = parseIsoDate(checkOutIso);
        if (from === null || to === null || to <= from) return null;
        return Math.round((to - from) / DAY_MS);
    };

    /** True when the check-in date is before the hotel's current date (both ISO). */
    const isPastCheckIn = (checkInIso, hotelTodayIso) => {
        const checkIn = parseIsoDate(checkInIso);
        const today = parseIsoDate(hotelTodayIso);
        return checkIn !== null && today !== null && checkIn < today;
    };

    /** dd/MM/yyyy, the application's display format for dates. */
    const formatDisplayDate = (iso) => {
        const match = ISO_DATE_PATTERN.exec(iso || "");
        return match ? `${match[3]}/${match[2]}/${match[1]}` : "";
    };

    /** Comma-grouped whole number, for example 1500000 -> "1,500,000". */
    const formatMoney = (value) => String(Math.round(value)).replace(/\B(?=(\d{3})+(?!\d))/g, ",");

    /** Removes the grouping the money input adds, so the value can be parsed or submitted. */
    const normalizeMoney = (text) => String(text || "").replaceAll(",", "").replaceAll(" ", "");

    /**
     * Classifies a typed nightly rate for VND (no fraction digits): empty, invalid text, a fraction that is not a whole
     * amount, not above zero, or ok. A fraction of only zeros (1500000.00) is still a whole amount, as on the server.
     */
    const parseRate = (text) => {
        const normalized = normalizeMoney(text);
        if (normalized === "") return { status: "empty", value: null };
        if (!/^\d+(\.\d*)?$/.test(normalized)) return { status: "invalid", value: null };
        const [whole, fraction = ""] = normalized.split(".");
        if (/[1-9]/.test(fraction)) return { status: "fraction", value: null };
        const value = Number(whole);
        if (!Number.isSafeInteger(value)) return { status: "invalid", value: null };
        if (value <= 0) return { status: "positive", value: null };
        return { status: "ok", value };
    };

    /** Informational room total: nightly rate x nights, or null when either is unknown. */
    const roomTotal = (rate, nights) => (rate === null || nights === null ? null : rate * nights);

    /** Informational estimated total: the sum of the known room totals. */
    const estimatedTotal = (totals) => totals.reduce((sum, total) => sum + (total === null ? 0 : total), 0);

    /**
     * Compares the adults with the selected rooms' adult capacity, for a soft warning only. Capacity is ADULT capacity
     * summed across the rooms. A room without a configured capacity makes the comparison impossible: it is reported,
     * never treated as zero or unlimited.
     *
     * @returns {{status: "none"|"ok"|"exceeded"|"notConfigured", capacity: number|null, unconfigured: string[]}}
     */
    const assessCapacity = (adults, rooms) => {
        const chosen = rooms.filter((room) => room && room.roomNumber);
        if (chosen.length === 0 || !Number.isInteger(adults) || adults < 1) {
            return { status: "none", capacity: null, unconfigured: [] };
        }
        const unconfigured = chosen.filter((room) => room.capacity === null || room.capacity === undefined)
            .map((room) => room.roomNumber);
        if (unconfigured.length > 0) {
            return { status: "notConfigured", capacity: null, unconfigured };
        }
        const capacity = chosen.reduce((sum, room) => sum + room.capacity, 0);
        return { status: adults > capacity ? "exceeded" : "ok", capacity, unconfigured: [] };
    };

    /** Notes length as the server counts it: a browser submits every line break as CRLF. */
    const notesLength = (text) => String(text || "").replace(/\r?\n/g, "\r\n").length;

    const text = (value, max) => (typeof value === "string" ? value.slice(0, max) : "");
    const uuid = (value) => (typeof value === "string" && UUID_PATTERN.test(value) ? value : "");
    const isoDate = (value) => (typeof value === "string" && ISO_DATE_PATTERN.test(value) ? value : "");

    /**
     * Rebuilds a saved state from untrusted storage content, keeping only the expected fields with the expected
     * types and lengths. Returns null when it does not look like a Create Reservation state at all.
     */
    const sanitizeState = (raw) => {
        if (!raw || typeof raw !== "object" || raw.v !== 1) return null;
        const guest = raw.guest && uuid(raw.guest.id)
            ? {
                id: raw.guest.id,
                code: text(raw.guest.code, 50),
                name: text(raw.guest.name, 300),
                phone: text(raw.guest.phone, 100),
                email: text(raw.guest.email, 255),
                nationality: text(raw.guest.nationality, 100),
            }
            : null;
        const accompanying = Array.isArray(raw.accompanying)
            ? raw.accompanying.filter((item) => item && uuid(item.id)).slice(0, 50)
                .map((item) => ({ id: item.id, label: text(item.label, 400) }))
            : [];
        const rooms = Array.isArray(raw.rooms)
            ? raw.rooms.slice(0, 50).map((room) => ({
                roomId: room && uuid(room.roomId),
                roomNumber: text(room && room.roomNumber, 50),
                rate: text(room && room.rate, 30),
            }))
            : [];
        return {
            v: 1,
            savedAt: Number.isFinite(raw.savedAt) ? raw.savedAt : 0,
            guest,
            accompanying,
            checkIn: isoDate(raw.checkIn),
            checkOut: isoDate(raw.checkOut),
            adults: text(raw.adults, 10),
            children: text(raw.children, 10),
            source: SOURCES.includes(raw.source) ? raw.source : "",
            ota: text(raw.ota, 255),
            rooms,
            contactName: text(raw.contactName, 200),
            contactPhone: text(raw.contactPhone, 100),
            contactEmail: text(raw.contactEmail, 255),
            notes: text(raw.notes, NOTES_MAX + 2000),
        };
    };

    const clearDraftState = (storage) => {
        try {
            storage.removeItem(STORAGE_KEY);
        } catch (ignored) {
            // Storage can be unavailable (private window, blocked site data); the page works without it.
        }
    };

    /** Saves the unsaved form input just before leaving for Create New Guest. Returns false when it could not be kept. */
    const saveDraftState = (storage, state, now) => {
        try {
            storage.setItem(STORAGE_KEY, JSON.stringify({ ...state, v: 1, savedAt: now }));
            return true;
        } catch (ignored) {
            return false;
        }
    };

    /** Reads the saved state; a missing, malformed or expired entry yields null and is removed. */
    const readDraftState = (storage, now) => {
        let parsed = null;
        try {
            const stored = storage.getItem(STORAGE_KEY);
            parsed = stored ? JSON.parse(stored) : null;
        } catch (ignored) {
            parsed = null;
        }
        const state = sanitizeState(parsed);
        if (!state || now - state.savedAt > STATE_TTL_MS || state.savedAt > now + 60000) {
            clearDraftState(storage);
            return null;
        }
        return state;
    };

    /**
     * Decides, once per page load, whether the saved state is restored. It is restored only when this load completes the
     * Create New Guest round trip: the server reports the Guest it created, the user came back from the Guest creation
     * page (Cancel / back link) or used the browser's Back button. Any other visit is unrelated, so a leftover state is
     * discarded. Either way the entry is removed here: it is consumed or stale, never kept for a later visit.
     */
    const resolveRestore = (storage, now, context) => {
        const state = readDraftState(storage, now);
        if (!state) return null;
        clearDraftState(storage);
        const returning = Boolean(context.createdGuestId) || Boolean(context.cameFromGuestCreation)
            || context.navigationType === "back_forward";
        return returning ? state : null;
    };

    /** True when the form holds nothing beyond its pristine defaults (so Cancel needs no discard confirmation). */
    const isPristine = (state, defaults) => {
        const blank = (value) => !String(value || "").trim();
        return !state.guest
            && state.accompanying.length === 0
            && blank(state.checkIn) && blank(state.checkOut)
            && String(state.adults) === String(defaults.adults)
            && String(state.children) === String(defaults.children)
            && blank(state.source) && blank(state.ota)
            && state.rooms.every((room) => !room.roomId && blank(room.rate))
            && blank(state.contactName) && blank(state.contactPhone) && blank(state.contactEmail)
            && blank(state.notes);
    };

    root.PmsReservationCreate = {
        NOTES_MAX,
        STATE_TTL_MS,
        STORAGE_KEY,
        assessCapacity,
        clearDraftState,
        estimatedTotal,
        formatDisplayDate,
        formatMoney,
        isPastCheckIn,
        isPristine,
        nightsBetween,
        normalizeMoney,
        notesLength,
        parseIsoDate,
        parseRate,
        readDraftState,
        resolveRestore,
        roomTotal,
        sanitizeState,
        saveDraftState,
    };
})(typeof window !== "undefined" ? window : globalThis);
