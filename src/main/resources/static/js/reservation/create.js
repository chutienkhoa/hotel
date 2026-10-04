(() => {
    "use strict";

    // Create Reservation page behaviour: Guest search, the date-aware room picker, live summary and totals, soft
    // warnings, client-side validation with the shared error dialog, and keeping the unsaved form across the Create New
    // Guest round trip. Presentation only: which Guests and Rooms exist, whether a room is bookable, and every
    // validation rule are decided by the server (lookup endpoints, Save as Draft and, later, Confirm). Nothing here
    // creates or confirms anything. The pure helpers live in create-model.js.

    const model = window.PmsReservationCreate;
    const form = document.getElementById("reservation-create-form");
    if (!model || !form) return;

    // ---------------------------------------------------------------------------------------------- text and helpers

    const messages = {};
    document.querySelectorAll("[data-create-messages] [data-msg]").forEach((node) => {
        messages[node.dataset.msg] = node.textContent;
    });
    const t = (key, ...args) => args.reduce(
        (text, arg, index) => text.replaceAll(`{${index}}`, String(arg)), messages[key] || key);

    const byId = (id) => document.getElementById(id);
    const config = form.dataset;
    const hotelToday = config.hotelToday || "";
    const defaults = { adults: config.defaultAdults || "1", children: config.defaultChildren || "0" };

    const create = (tag, className, text) => {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    };

    const initials = (name) => {
        const words = String(name || "").trim().split(/\s+/).filter(Boolean);
        if (words.length === 0) return "?";
        const first = words[0][0];
        const last = words.length > 1 ? words[words.length - 1][0] : "";
        return (first + last).toUpperCase();
    };

    const nightsText = (count) => t(count === 1 ? "nightsOne" : "nightsOther", count);
    const capacityText = (capacity) => (capacity === null || capacity === undefined
        ? t("capacityUnknown")
        : t(capacity === 1 ? "capacityOne" : "capacityOther", capacity));

    const focusControl = (control) => {
        if (!control || control.disabled) return;
        control.scrollIntoView({ block: "center" });
        control.focus({ preventScroll: true });
    };

    // ---------------------------------------------------------------------------------------------- form elements

    const guestIdInput = byId("guestId");
    // The Stay range picker is only the UI for these two existing, submitted values (hidden inputs).
    const checkInInput = byId("checkInDate");
    const checkOutInput = byId("checkOutDate");
    const stayTrigger = byId("rc-stay-trigger");
    const adultInput = byId("adultCount");
    const childInput = byId("childCount");
    const sourceSelect = byId("source");
    const otaField = form.querySelector("[data-ota-field]");
    const otaInput = byId("otaBookingReference");
    const contactInputs = [byId("bookingContactName"), byId("bookingContactPhone"), byId("bookingContactEmail")];
    const notesInput = byId("notes");
    const nightsOutput = form.querySelector("[data-nights]");
    const roomRowsBody = form.querySelector("[data-room-rows]");
    const rowTemplate = byId("rc-room-row-template");
    const addRoomButton = form.querySelector("[data-add-room]");
    const roomsError = byId("rooms-error");
    const accompanyingError = byId("accompanyingGuestIds-error");
    const saveButton = document.querySelector("[data-save-draft]");

    // Error text is shown in the field's own <p class="field-error" id="<field>-error">; the server renders it for a
    // rejected submit and this script fills it for a problem found in the browser.
    const setError = (fieldId, message, controls) => {
        const target = byId(`${fieldId}-error`);
        if (target) target.textContent = message || "";
        (controls || []).forEach((control) => {
            if (!control) return;
            if (message) control.setAttribute("aria-invalid", "true");
            else control.removeAttribute("aria-invalid");
        });
    };

    // ---------------------------------------------------------------------------------------------- Primary Guest

    let currentGuest = null;

    const guestFromDataset = (data) => (data.id
        ? {
            id: data.id,
            code: data.code || "",
            name: data.name || "",
            phone: data.phone || "",
            email: data.email || "",
            nationality: data.nationality || "",
        }
        : null);

    const guestCard = form.querySelector("[data-guest-card]");
    const guestViewLink = form.querySelector("[data-guest-view]");
    const guestCreatedNotice = form.querySelector("[data-guest-created-notice]");

    const renderGuestCard = () => {
        guestCard.hidden = !currentGuest;
        if (!currentGuest) return;
        const absent = t("guestNotProvided");
        guestCard.querySelector("[data-guest-initials]").textContent = initials(currentGuest.name);
        guestCard.querySelector("[data-guest-name]").textContent = currentGuest.name || "—";
        guestCard.querySelector("[data-guest-code]").textContent = currentGuest.code || "—";
        guestCard.querySelector("[data-guest-phone]").textContent = currentGuest.phone || absent;
        guestCard.querySelector("[data-guest-email]").textContent = currentGuest.email || absent;
        guestCard.querySelector("[data-guest-nationality]").textContent = currentGuest.nationality || absent;
        // View Guest exists in the markup only for a user allowed to open Guest Detail; it opens beside the form so
        // nothing typed is lost.
        if (guestViewLink && config.guestUrl) {
            guestViewLink.href = config.guestUrl.replace("GUEST_ID", encodeURIComponent(currentGuest.id));
            guestViewLink.hidden = false;
        }
    };

    const setPrimaryGuest = (guest, { fromUser } = {}) => {
        currentGuest = guest;
        guestIdInput.value = guest ? guest.id : "";
        renderGuestCard();
        if (fromUser) {
            setError("guestId", "", [byId("guestSearch")]);
            if (guestCreatedNotice) guestCreatedNotice.hidden = true;
        }
        checkAccompanyingConflict();
        render();
    };

    // ---------------------------------------------------------------------------------------------- Guest search

    const createGuestPicker = (root, { disabledReason, onChoose }) => {
        const input = root.querySelector("[data-picker-input]");
        const popup = root.querySelector("[data-picker-popup]");
        const list = root.querySelector("[data-picker-list]");
        const message = root.querySelector("[data-picker-message]");
        const status = root.querySelector("[data-picker-status]");
        let items = [];
        let active = -1;
        let sequence = 0;
        let timer = null;
        let controller = null;

        const setActive = (index) => {
            active = index;
            list.querySelectorAll(".rc-option").forEach((option, position) => {
                option.classList.toggle("is-active", position === index);
                option.setAttribute("aria-selected", position === index ? "true" : "false");
                if (position === index) {
                    input.setAttribute("aria-activedescendant", option.id);
                    option.scrollIntoView({ block: "nearest" });
                }
            });
            if (index < 0) input.removeAttribute("aria-activedescendant");
        };

        const open = () => {
            popup.hidden = false;
            input.setAttribute("aria-expanded", "true");
        };

        const close = () => {
            popup.hidden = true;
            input.setAttribute("aria-expanded", "false");
            input.removeAttribute("aria-activedescendant");
            active = -1;
        };

        const showMessage = (text) => {
            list.replaceChildren();
            items = [];
            message.textContent = text;
            open();
        };

        const choose = (guest) => {
            const reason = disabledReason(guest);
            if (reason) {
                status.textContent = reason;
                return;
            }
            input.value = "";
            close();
            list.replaceChildren();
            items = [];
            onChoose(guest);
        };

        const render = () => {
            message.textContent = "";
            list.replaceChildren(...items.map((guest, index) => {
                const reason = disabledReason(guest);
                const option = create("li", "rc-option rc-option--guest");
                option.id = `${input.id}-option-${index}`;
                option.setAttribute("role", "option");
                option.setAttribute("aria-selected", "false");
                if (reason) option.setAttribute("aria-disabled", "true");
                const main = create("span", "rc-option__main");
                main.append(create("strong", "", guest.fullName || guest.guestCode), create("span", "", guest.guestCode));
                const side = create("span", "rc-option__side");
                const contact = [guest.phone, guest.email].filter(Boolean);
                contact.forEach((value) => side.appendChild(create("span", "", value)));
                if (reason) side.appendChild(create("span", "", reason));
                option.append(create("span", "rc-avatar", initials(guest.fullName)), main, side);
                // mousedown (not click) keeps the input focused while a result is picked.
                option.addEventListener("mousedown", (event) => event.preventDefault());
                option.addEventListener("click", () => choose(guest));
                return option;
            }));
            status.textContent = t("guestSearchCount", items.length);
            open();
            setActive(items.length > 0 ? 0 : -1);
        };

        const search = (query) => {
            if (controller) controller.abort();
            const current = ++sequence;
            if (!query) {
                close();
                list.replaceChildren();
                return;
            }
            showMessage(t("guestSearchLoading"));
            controller = new AbortController();
            fetch(`${config.guestSearchUrl}?query=${encodeURIComponent(query)}`, {
                credentials: "same-origin",
                headers: { Accept: "application/json" },
                signal: controller.signal,
            })
                .then((response) => {
                    if (!response.ok) throw new Error(String(response.status));
                    return response.json();
                })
                .then((guests) => {
                    if (current !== sequence) return;
                    items = guests;
                    if (guests.length === 0) {
                        showMessage(t("guestSearchNoResults"));
                        status.textContent = t("guestSearchNoResults");
                    } else {
                        render();
                    }
                })
                .catch((error) => {
                    if (error && error.name === "AbortError") return;
                    if (current === sequence) showMessage(t("guestSearchError"));
                });
        };

        input.addEventListener("input", () => {
            window.clearTimeout(timer);
            timer = window.setTimeout(() => search(input.value.trim()), 250);
        });
        input.addEventListener("focus", () => {
            if (items.length > 0 && input.value.trim()) open();
        });
        input.addEventListener("keydown", (event) => {
            if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                if (items.length === 0) return;
                event.preventDefault();
                if (popup.hidden) open();
                const step = event.key === "ArrowDown" ? 1 : -1;
                setActive((active + step + items.length) % items.length);
            } else if (event.key === "Enter") {
                // Enter picks the highlighted result; it must never submit the reservation form.
                event.preventDefault();
                if (!popup.hidden && active >= 0 && items[active]) choose(items[active]);
            } else if (event.key === "Escape") {
                if (!popup.hidden) {
                    event.preventDefault();
                    close();
                }
            } else if (event.key === "Tab") {
                close();
            }
        });
        document.addEventListener("pointerdown", (event) => {
            if (!root.contains(event.target)) close();
        });

        const announce = (text) => {
            status.textContent = text;
        };

        return { input, close, announce };
    };

    const primaryPicker = createGuestPicker(form.querySelector('[data-guest-picker="primary"]'), {
        disabledReason: () => null,
        onChoose: (guest) => {
            setPrimaryGuest(
                guestFromDataset({
                    id: guest.id,
                    code: guest.guestCode,
                    name: guest.fullName,
                    phone: guest.phone,
                    email: guest.email,
                    nationality: guest.nationality,
                }),
                { fromUser: true });
            primaryPicker.announce(`${t("guestSelected")}: ${guest.fullName || guest.guestCode}`);
        },
    });

    // ---------------------------------------------------------------------------------------------- Accompanying Guests

    const accompanyingList = form.querySelector("[data-accompanying-list]");
    const accompanyingEmpty = form.querySelector("[data-accompanying-empty]");

    const accompanyingGuests = () => Array.from(accompanyingList.querySelectorAll("li")).map((item) => ({
        id: item.dataset.guestId,
        label: item.dataset.guestLabel || "",
    }));

    // The Primary Guest cannot also be an accompanying guest (server rule); shown until one of them is changed.
    function checkAccompanyingConflict() {
        const conflict = currentGuest && accompanyingGuests().some((guest) => guest.id === currentGuest.id);
        setError("accompanyingGuestIds", conflict ? t("accompanyingPrimary") : "", [byId("accompanyingSearch")]);
        return Boolean(conflict);
    }

    const announce = (text) => {
        const status = form.querySelector('[data-guest-picker="accompanying"] [data-picker-status]');
        if (status) status.textContent = text;
    };

    const addAccompanying = (guest) => {
        const label = `${guest.code} · ${guest.name}`;
        const item = create("li");
        item.dataset.guestId = guest.id;
        item.dataset.guestLabel = label;
        const hidden = create("input");
        hidden.type = "hidden";
        hidden.name = "accompanyingGuestIds";
        hidden.value = guest.id;
        const remove = create("button", "rc-chip-remove");
        remove.type = "button";
        remove.dataset.accompanyingRemove = "";
        remove.setAttribute("aria-label", `${t("removeLabel")}: ${guest.code}`);
        remove.innerHTML = '<svg aria-hidden="true" fill="none" height="16" stroke="currentColor" stroke-linecap="round" '
            + 'stroke-width="1.8" viewBox="0 0 24 24" width="16"><path d="M6 6l12 12M18 6 6 18"></path></svg>';
        item.append(create("span", "", label), hidden, remove);
        accompanyingList.appendChild(item);
        accompanyingEmpty.hidden = true;
    };

    accompanyingList.addEventListener("click", (event) => {
        const remove = event.target.closest("[data-accompanying-remove]");
        if (!remove) return;
        const item = remove.closest("li");
        const label = item.dataset.guestLabel || "";
        item.remove();
        accompanyingEmpty.hidden = accompanyingList.children.length > 0;
        announce(t("accompanyingRemoved", label));
        checkAccompanyingConflict();
        focusControl(byId("accompanyingSearch"));
        render();
    });

    const accompanyingPicker = createGuestPicker(form.querySelector('[data-guest-picker="accompanying"]'), {
        disabledReason: (guest) => {
            if (currentGuest && guest.id === currentGuest.id) return t("guestAlreadyPrimary");
            return accompanyingGuests().some((existing) => existing.id === guest.id) ? t("guestAlreadyAdded") : null;
        },
        onChoose: (guest) => {
            addAccompanying({ id: guest.id, code: guest.guestCode, name: guest.fullName });
            announce(t("accompanyingAdded", `${guest.guestCode} · ${guest.fullName}`));
            checkAccompanyingConflict();
            render();
        },
    });

    // ---------------------------------------------------------------------------------------------- Rooms & Rates

    // availability.status: prompt (dates missing) | invalid (check-out not after check-in) | loading | error | ready.
    const availability = { status: "prompt", rooms: [], checkIn: "", checkOut: "" };
    const rows = [];
    let rowSequence = 0;
    let availabilityRequest = 0;
    let availabilityController = null;

    const availabilityNote = form.querySelector("[data-room-availability]");
    const availabilityText = form.querySelector("[data-room-availability-text]");
    const retryButton = form.querySelector("[data-room-retry]");
    const clearedNotice = form.querySelector("[data-rooms-cleared]");
    const capacityWarning = form.querySelector("[data-capacity-warning]");
    const capacityWarningText = form.querySelector("[data-capacity-warning-text]");
    const capacityTotal = form.querySelector("[data-capacity-total]");

    const roomDescription = (typeName, capacity) => `${typeName || "—"} · ${capacityText(capacity)}`;

    const usedRoomIds = (exceptRow) => new Set(
        rows.filter((row) => row !== exceptRow && row.roomId).map((row) => row.roomId));

    // known: the row's Room Type and capacity come from the lookup for the current stay (not just a restored id).
    const assignRoom = (row, room) => {
        row.known = Boolean(room);
        row.roomId = room ? room.id : "";
        row.roomNumber = room ? room.roomNumber : "";
        row.typeName = room ? room.roomTypeName || "" : "";
        row.capacity = room && room.adultCapacity !== undefined ? room.adultCapacity : null;
        row.roomIdInput.value = row.roomId;
    };

    const renderRow = (row, index) => {
        row.indexCell.textContent = String(index + 1);
        row.roomIdInput.name = `rooms[${index}].roomId`;
        row.rateInput.name = `rooms[${index}].nightlyRate`;
        row.rateInput.id = `rooms-${index}-nightlyRate`;
        row.trigger.id = `rooms-${index}-roomId`;
        row.trigger.setAttribute("aria-label", `${t("roomsSelect")} (${index + 1})`);
        row.rateInput.setAttribute("aria-label", `${t("rateLabel")} (${index + 1})`);
        row.triggerLabel.textContent = row.roomNumber || t("roomsSelect");
        row.trigger.dataset.empty = row.roomId ? "false" : "true";
        row.typeCell.textContent = row.known ? row.typeName || "—" : "—";
        row.capacityCell.textContent = row.known ? capacityText(row.capacity) : "—";
        row.trigger.disabled = availability.status !== "ready";
        row.removeButton.disabled = rows.length === 1;
        row.removeButton.setAttribute("aria-label", t("roomsRemove", index + 1));
        // The nightly rate belongs to a chosen room: without one it is disabled and holds no value.
        row.rateInput.disabled = !row.roomId;
        if (!row.roomId) {
            row.rateInput.value = "";
            setRowError(row, "rate", "");
        }
        const rate = model.parseRate(row.rateInput.value);
        const total = model.roomTotal(rate.value, nightsCount());
        row.total = total;
        row.totalCell.textContent = total === null ? "—" : model.formatMoney(total);
    };

    const updateRowErrorIds = (row, index) => {
        // Each row's error paragraphs are described by the controls they belong to.
        const roomErrorId = `rooms-${index}-roomId-error`;
        const rateErrorId = `rooms-${index}-nightlyRate-error`;
        row.roomError.id = roomErrorId;
        row.rateError.id = rateErrorId;
        row.trigger.setAttribute("aria-describedby", roomErrorId);
        row.rateInput.setAttribute("aria-describedby", rateErrorId);
    };

    const setRowError = (row, which, message) => {
        const holder = which === "room" ? row.roomError : row.rateError;
        const control = which === "room" ? row.trigger : row.rateInput;
        holder.textContent = message || "";
        if (message) control.setAttribute("aria-invalid", "true");
        else control.removeAttribute("aria-invalid");
    };

    const buildRow = (initial) => {
        const fragment = rowTemplate.content.firstElementChild.cloneNode(true);
        const row = {
            key: ++rowSequence,
            element: fragment,
            roomId: "",
            roomNumber: "",
            typeName: "",
            capacity: null,
            known: false,
            total: null,
            indexCell: fragment.querySelector("[data-row-index]"),
            roomIdInput: fragment.querySelector("[data-room-id]"),
            trigger: fragment.querySelector("[data-room-trigger]"),
            triggerLabel: fragment.querySelector("[data-room-trigger-label]"),
            popup: fragment.querySelector("[data-room-popup]"),
            search: fragment.querySelector("[data-room-search]"),
            list: fragment.querySelector("[data-room-list]"),
            message: fragment.querySelector("[data-room-message]"),
            roomError: fragment.querySelector("[data-room-error]"),
            typeCell: fragment.querySelector("[data-room-type]"),
            capacityCell: fragment.querySelector("[data-room-capacity]"),
            rateInput: fragment.querySelector("[data-rate]"),
            rateError: fragment.querySelector("[data-rate-error]"),
            totalCell: fragment.querySelector("[data-room-total]"),
            removeButton: fragment.querySelector("[data-room-remove]"),
        };
        if (initial && initial.roomId) {
            row.roomId = initial.roomId;
            row.roomNumber = initial.roomNumber || "";
            row.roomIdInput.value = initial.roomId;
        }
        row.rateInput.value = initial && initial.rate ? initial.rate : "";
        if (window.PmsNumericInput) window.PmsNumericInput.initialize(row.rateInput);
        wireRow(row);
        return row;
    };

    // --- room picker popup (one per row) ---------------------------------------------------------------------------

    let openRow = null;

    const placePopup = (row) => {
        const rect = row.trigger.getBoundingClientRect();
        const margin = 8;
        const width = Math.min(Math.max(rect.width, 320), window.innerWidth - 2 * margin);
        const left = Math.max(margin, Math.min(rect.left, window.innerWidth - width - margin));
        row.popup.style.width = `${width}px`;
        row.popup.style.left = `${left}px`;
        const popupHeight = row.popup.offsetHeight;
        const below = window.innerHeight - rect.bottom - margin;
        if (popupHeight > below && rect.top - margin > below) {
            row.popup.style.top = `${Math.max(margin, rect.top - popupHeight - 4)}px`;
        } else {
            row.popup.style.top = `${rect.bottom + 4}px`;
        }
    };

    const closePicker = (row, returnFocus) => {
        if (!row || row.popup.hidden) return;
        row.popup.hidden = true;
        row.trigger.setAttribute("aria-expanded", "false");
        row.search.removeAttribute("aria-activedescendant");
        if (openRow === row) openRow = null;
        if (returnFocus) row.trigger.focus();
    };

    const renderRoomOptions = (row) => {
        const query = row.search.value.trim().toLowerCase();
        const used = usedRoomIds(row);
        const matches = availability.rooms.filter((room) => !query
            || `${room.roomNumber} ${room.roomTypeName || ""}`.toLowerCase().includes(query));
        row.list.replaceChildren(...matches.map((room, index) => {
            const option = create("li", "rc-option rc-option--room");
            option.id = `room-option-${row.key}-${index}`;
            option.setAttribute("role", "option");
            option.setAttribute("aria-selected", room.id === row.roomId ? "true" : "false");
            option.dataset.roomId = room.id;
            const main = create("span", "rc-option__main");
            main.append(create("strong", "", room.roomNumber), create("span", "", roomDescription(room.roomTypeName, room.adultCapacity)));
            const taken = used.has(room.id);
            if (taken) option.setAttribute("aria-disabled", "true");
            option.append(main, create("span", taken ? "rc-badge rc-badge--muted" : "rc-badge",
                taken ? t("roomsAddedElsewhere") : t("roomsAvailable")));
            option.addEventListener("mousedown", (event) => event.preventDefault());
            option.addEventListener("click", () => chooseRoom(row, room));
            return option;
        }));
        row.message.textContent = matches.length === 0
            ? (availability.rooms.length === 0 ? t("roomsNone") : t("roomsNoMatch"))
            : t("roomsOptionCount", matches.length);
        const first = row.list.querySelector('.rc-option:not([aria-disabled="true"])');
        setActiveRoomOption(row, first);
    };

    const setActiveRoomOption = (row, option) => {
        row.list.querySelectorAll(".rc-option").forEach((candidate) => {
            candidate.classList.toggle("is-active", candidate === option);
        });
        if (option) {
            row.search.setAttribute("aria-activedescendant", option.id);
            option.scrollIntoView({ block: "nearest" });
        } else {
            row.search.removeAttribute("aria-activedescendant");
        }
    };

    const openPicker = (row) => {
        if (row.trigger.disabled) return;
        if (openRow && openRow !== row) closePicker(openRow, false);
        row.search.value = "";
        row.popup.hidden = false;
        row.trigger.setAttribute("aria-expanded", "true");
        openRow = row;
        renderRoomOptions(row);
        placePopup(row);
        row.search.focus();
    };

    const chooseRoom = (row, room) => {
        if (usedRoomIds(row).has(room.id)) return;
        assignRoom(row, room);
        setRowError(row, "room", "");
        if (roomsError) roomsError.textContent = "";
        if (clearedNotice) clearedNotice.hidden = true;
        closePicker(row, false);
        render();
        row.rateInput.focus();
    };

    function wireRow(row) {
        row.trigger.addEventListener("click", () => {
            if (row.popup.hidden) openPicker(row);
            else closePicker(row, true);
        });
        row.trigger.addEventListener("keydown", (event) => {
            if (event.key === "ArrowDown" && row.popup.hidden) {
                event.preventDefault();
                openPicker(row);
            }
        });
        row.search.addEventListener("input", () => {
            renderRoomOptions(row);
            placePopup(row);
        });
        row.search.addEventListener("keydown", (event) => {
            const options = Array.from(row.list.querySelectorAll('.rc-option:not([aria-disabled="true"])'));
            const activeIndex = options.findIndex((option) => option.classList.contains("is-active"));
            if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                event.preventDefault();
                if (options.length === 0) return;
                const step = event.key === "ArrowDown" ? 1 : -1;
                setActiveRoomOption(row, options[(activeIndex + step + options.length) % options.length]);
            } else if (event.key === "Enter") {
                // Enter picks the highlighted room; it must never submit the reservation form.
                event.preventDefault();
                const option = options[activeIndex];
                const room = option && availability.rooms.find((candidate) => candidate.id === option.dataset.roomId);
                if (room) chooseRoom(row, room);
            } else if (event.key === "Escape") {
                event.preventDefault();
                closePicker(row, true);
            } else if (event.key === "Tab") {
                closePicker(row, false);
            }
        });
        row.rateInput.addEventListener("input", () => {
            setRowError(row, "rate", "");
            render();
        });
        row.removeButton.addEventListener("click", () => removeRow(row));
    }

    document.addEventListener("pointerdown", (event) => {
        if (openRow && !openRow.element.contains(event.target)) closePicker(openRow, false);
    });
    const repositionOpenPicker = () => {
        if (openRow) placePopup(openRow);
    };
    window.addEventListener("resize", repositionOpenPicker);
    window.addEventListener("scroll", repositionOpenPicker, true);

    const addRow = (initial, { focus } = {}) => {
        const row = buildRow(initial);
        rows.push(row);
        roomRowsBody.appendChild(row.element);
        updateRowErrorIds(row, rows.length - 1);
        if (focus) row.trigger.focus();
        return row;
    };

    const removeRow = (row) => {
        if (rows.length <= 1) return;
        const index = rows.indexOf(row);
        closePicker(row, false);
        rows.splice(index, 1);
        row.element.remove();
        rows.forEach((remaining, position) => updateRowErrorIds(remaining, position));
        render();
        const next = rows[Math.min(index, rows.length - 1)];
        focusControl(next.trigger.disabled ? next.removeButton : next.trigger);
    };

    addRoomButton.addEventListener("click", () => {
        addRow(null, { focus: true });
        render();
    });

    // --- availability for the selected stay ------------------------------------------------------------------------

    function nightsCount() {
        return model.nightsBetween(checkInInput.value, checkOutInput.value);
    }

    // After a lookup, a selected room that is no longer offered for the stay is cleared with a clear message, never kept
    // as if it were still available.
    const reconcileRooms = () => {
        const offered = new Map(availability.rooms.map((room) => [room.id, room]));
        let cleared = 0;
        rows.forEach((row) => {
            if (!row.roomId) return;
            const room = offered.get(row.roomId);
            if (room) {
                assignRoom(row, room);
                setRowError(row, "room", "");
            } else {
                cleared += 1;
                const number = row.roomNumber || "";
                assignRoom(row, null);
                setRowError(row, "room", t("roomsCleared", number));
            }
        });
        if (clearedNotice) clearedNotice.hidden = cleared === 0;
    };

    const loadAvailability = () => {
        const checkIn = checkInInput.value;
        const checkOut = checkOutInput.value;
        if (availabilityController) availabilityController.abort();
        const current = ++availabilityRequest;
        availability.checkIn = checkIn;
        availability.checkOut = checkOut;
        if (clearedNotice) clearedNotice.hidden = true;
        if (!checkIn || !checkOut) {
            availability.status = "prompt";
            availability.rooms = [];
            rows.forEach((row) => closePicker(row, false));
            render();
            return;
        }
        if (nightsCount() === null) {
            availability.status = "invalid";
            availability.rooms = [];
            rows.forEach((row) => closePicker(row, false));
            render();
            return;
        }
        availability.status = "loading";
        render();
        availabilityController = new AbortController();
        fetch(`${config.roomLookupUrl}?checkInDate=${encodeURIComponent(checkIn)}&checkOutDate=${encodeURIComponent(checkOut)}`, {
            credentials: "same-origin",
            headers: { Accept: "application/json" },
            signal: availabilityController.signal,
        })
            .then((response) => {
                if (!response.ok) throw new Error(String(response.status));
                return response.json();
            })
            .then((rooms) => {
                if (current !== availabilityRequest) return;
                availability.rooms = rooms;
                availability.status = "ready";
                reconcileRooms();
                render();
            })
            .catch((error) => {
                if (error && error.name === "AbortError") return;
                if (current !== availabilityRequest) return;
                availability.status = "error";
                availability.rooms = [];
                render();
            });
    };

    retryButton.addEventListener("click", loadAvailability);

    // ---------------------------------------------------------------------------------------------- Stay Details

    const sourceValue = () => sourceSelect.value;
    const isOtaSource = () => sourceValue() !== "" && sourceValue() !== "DIRECT";

    const updateOtaField = () => {
        const needed = isOtaSource();
        otaField.hidden = !needed;
        // A hidden reference is not applicable and must not be submitted; DIRECT also clears what was typed.
        otaInput.disabled = !needed;
        if (sourceValue() === "DIRECT") otaInput.value = "";
        otaInput.setAttribute("aria-required", needed ? "true" : "false");
        if (!needed) setError("otaBookingReference", "", [otaInput]);
    };

    // Runs after the Stay picker applies or clears a range: refreshes Nights and the summary, and re-checks the rooms
    // against the new dates.
    const onDatesChanged = () => {
        setError("checkInDate", "", [stayTrigger]);
        setError("checkOutDate", "", [stayTrigger]);
        // The picker only applies an ordered range; a restored or re-rendered pair can still be out of order.
        if (checkInInput.value && checkOutInput.value && nightsCount() === null) {
            setError("checkOutDate", t("datesOrder"), [stayTrigger]);
        }
        loadAvailability();
        render();
    };

    const stayPicker = window.PmsStayRangePicker
        ? window.PmsStayRangePicker.init(form.querySelector("[data-stay-picker]"), {
            onApply: onDatesChanged,
            onClear: onDatesChanged,
        })
        : null;

    // ---------------------------------------------------------------------------------------------- rendering

    const pastCheckInNotice = form.querySelector("[data-past-check-in]");
    const contactHint = form.querySelector("[data-contact-hint-text]");
    const notesCounter = form.querySelector("[data-notes-counter]");
    const summary = form.querySelector("[data-summary]");

    const summaryText = (selector, text) => {
        summary.querySelector(selector).textContent = text;
    };

    const renderAvailabilityNote = () => {
        retryButton.hidden = availability.status !== "error";
        const count = nightsCount();
        switch (availability.status) {
        case "invalid":
            availabilityText.textContent = t("roomsInvalidDates");
            break;
        case "loading":
            availabilityText.textContent = t("roomsLoading");
            break;
        case "error":
            availabilityText.textContent = t("roomsLoadError");
            break;
        case "ready":
            availabilityText.textContent = availability.rooms.length === 0
                ? t("roomsNone")
                : t("roomsAvailabilityNote", model.formatDisplayDate(availability.checkIn),
                    model.formatDisplayDate(availability.checkOut), nightsText(count));
            break;
        default:
            availabilityText.textContent = t("roomsPrompt");
        }
    };

    const renderCapacity = () => {
        const adults = Number.parseInt(adultInput.value, 10);
        const assessment = model.assessCapacity(adults, rows.filter((row) => row.roomId && row.known).map((row) => ({
            roomNumber: row.roomNumber,
            capacity: row.capacity,
        })));
        // A soft warning only: Save as Draft is never blocked by capacity; Confirm re-checks it.
        capacityWarning.hidden = assessment.status !== "exceeded" && assessment.status !== "notConfigured";
        if (assessment.status === "exceeded") {
            capacityWarningText.textContent = t("capacityExceeded", adults, assessment.capacity);
        } else if (assessment.status === "notConfigured") {
            capacityWarningText.textContent = t("capacityNotConfigured", assessment.unconfigured.join(", "));
        }
        const known = assessment.capacity !== null;
        capacityTotal.hidden = !known;
        if (known) capacityTotal.textContent = t("capacityTotal", assessment.capacity);
    };

    const renderSummary = () => {
        const count = nightsCount();
        const rate = (row) => model.parseRate(row.rateInput.value);
        summaryText("[data-summary-guest]", currentGuest ? currentGuest.name || currentGuest.code : t("summaryNoGuest"));
        summaryText("[data-summary-guest-code]", currentGuest ? currentGuest.code : "");
        const stay = checkInInput.value && checkOutInput.value
            ? `${model.formatDisplayDate(checkInInput.value)} → ${model.formatDisplayDate(checkOutInput.value)}`
            : t("summaryNoStay");
        summaryText("[data-summary-stay]", stay);
        summaryText("[data-summary-nights]", count === null ? "" : nightsText(count));

        const chosen = rows.filter((row) => row.roomId).length;
        summaryText("[data-summary-room-count]", chosen === 0
            ? t("summaryNoRooms") : t(chosen === 1 ? "summaryRoomsOne" : "summaryRoomsOther", chosen));
        // One Label | Value row per room: the room, its type and capacity, rate x nights, then the room total.
        const list = summary.querySelector("[data-summary-rooms]");
        list.replaceChildren(...rows.map((row, index) => {
            const item = create("div", "rc-summary__row");
            const value = create("dd");
            value.appendChild(create("strong", "", row.roomId ? row.roomNumber : t("summaryRoomPending")));
            if (row.roomId && row.known) value.appendChild(create("span", "", roomDescription(row.typeName, row.capacity)));
            const parsed = rate(row);
            if (row.roomId && parsed.value !== null && count !== null) {
                value.appendChild(create("span", "", `${model.formatMoney(parsed.value)} VND × ${nightsText(count)}`));
            }
            if (row.total !== null) value.appendChild(create("span", "rc-summary__room-total", `${model.formatMoney(row.total)} VND`));
            item.append(create("dt", "", t("summaryRoomLabel", index + 1)), value);
            return item;
        }));

        summaryText("[data-summary-adults]", adultInput.value.trim() || "—");
        summaryText("[data-summary-children]", childInput.value.trim() || "—");
        summaryText("[data-summary-accompanying]", String(accompanyingGuests().length));
        const option = sourceSelect.options[sourceSelect.selectedIndex];
        summaryText("[data-summary-source]", sourceValue() ? option.textContent : "—");
        const otaRow = summary.querySelector("[data-summary-ota-row]");
        otaRow.hidden = !(isOtaSource() && otaInput.value.trim());
        summaryText("[data-summary-ota]", otaInput.value.trim());
        summaryText("[data-summary-total]", `${model.formatMoney(model.estimatedTotal(rows.map((row) => row.total)))} VND`);
    };

    function render() {
        const count = nightsCount();
        nightsOutput.textContent = count === null ? "—" : String(count);
        pastCheckInNotice.hidden = !model.isPastCheckIn(checkInInput.value, hotelToday);
        rows.forEach(renderRow);
        renderAvailabilityNote();
        renderCapacity();
        const allBlank = contactInputs.every((input) => !input.value.trim());
        contactHint.textContent = allBlank ? t("contactHintBlank") : t("contactHintFilled");
        const length = model.notesLength(notesInput.value);
        notesCounter.textContent = t("notesCounter", length, model.NOTES_MAX);
        notesCounter.classList.toggle("rc-counter--over", length > model.NOTES_MAX);
        renderSummary();
    }

    // ---------------------------------------------------------------------------------------------- form state

    const collectState = () => ({
        v: 1,
        guest: currentGuest,
        accompanying: accompanyingGuests(),
        checkIn: checkInInput.value,
        checkOut: checkOutInput.value,
        adults: adultInput.value,
        children: childInput.value,
        source: sourceSelect.value,
        ota: otaInput.value,
        rooms: rows.map((row) => ({ roomId: row.roomId, roomNumber: row.roomNumber, rate: row.rateInput.value })),
        contactName: contactInputs[0].value,
        contactPhone: contactInputs[1].value,
        contactEmail: contactInputs[2].value,
        notes: notesInput.value,
    });

    const setStay = (checkIn, checkOut) => {
        checkInInput.value = checkIn || "";
        checkOutInput.value = checkOut || "";
        if (stayPicker) stayPicker.setRange(checkIn, checkOut);
    };

    // Restores the unsaved input kept across the Create New Guest round trip. The Guest the server reports as just
    // created is applied by the caller afterwards and always wins over the Primary Guest that was selected before.
    const applyState = (state) => {
        if (state.guest) setPrimaryGuest(state.guest);
        accompanyingList.replaceChildren();
        state.accompanying.forEach((guest) => {
            const [code, ...rest] = guest.label.split(" · ");
            addAccompanying({ id: guest.id, code, name: rest.join(" · ") });
        });
        accompanyingEmpty.hidden = accompanyingList.children.length > 0;
        setStay(state.checkIn, state.checkOut);
        adultInput.value = state.adults || defaults.adults;
        childInput.value = state.children || defaults.children;
        sourceSelect.value = state.source;
        updateOtaField();
        otaInput.value = state.ota;
        contactInputs[0].value = state.contactName;
        contactInputs[1].value = state.contactPhone;
        contactInputs[2].value = state.contactEmail;
        notesInput.value = state.notes;
        rows.splice(0).forEach((row) => row.element.remove());
        const restored = state.rooms.length > 0 ? state.rooms : [null];
        restored.forEach((room) => addRow(room ? {
            roomId: room.roomId,
            roomNumber: room.roomNumber,
            rate: room.rate,
        } : null));
    };

    // ---------------------------------------------------------------------------------------------- validation

    const clearAllErrors = () => {
        form.querySelectorAll(".field-error").forEach((node) => {
            node.textContent = "";
        });
        form.querySelectorAll('[aria-invalid="true"]').forEach((node) => node.removeAttribute("aria-invalid"));
    };

    // Problems found in the browser, in page order: { message, control, mark: () => void }. The server validates the
    // same rules again; this only spares a round trip and points at the field.
    const collectProblems = () => {
        const problems = [];
        const add = (message, control, show) => problems.push({ message, control, show });
        const rowMessage = (index, message) => t("errorRoomRow", index + 1, message);

        if (!guestIdInput.value) {
            add(t("vGuest"), byId("guestSearch"), () => setError("guestId", t("vGuest"), [byId("guestSearch")]));
        }
        if (!checkInInput.value) {
            add(t("vCheckIn"), stayTrigger,
                () => setError("checkInDate", t("vCheckIn"), [stayTrigger]));
        }
        if (!checkOutInput.value) {
            add(t("vCheckOut"), stayTrigger,
                () => setError("checkOutDate", t("vCheckOut"), [stayTrigger]));
        } else if (checkInInput.value && nightsCount() === null) {
            add(t("datesOrder"), stayTrigger,
                () => setError("checkOutDate", t("datesOrder"), [stayTrigger]));
        }
        const adults = adultInput.value.trim();
        if (!adults) {
            add(t("vAdultsRequired"), adultInput, () => setError("adultCount", t("vAdultsRequired"), [adultInput]));
        } else if (!/^\d+$/.test(adults) || Number(adults) < 1) {
            add(t("vAdultsMin"), adultInput, () => setError("adultCount", t("vAdultsMin"), [adultInput]));
        }
        const children = childInput.value.trim();
        if (!children) {
            add(t("vChildrenRequired"), childInput, () => setError("childCount", t("vChildrenRequired"), [childInput]));
        } else if (!/^\d+$/.test(children)) {
            add(t("vChildrenMin"), childInput, () => setError("childCount", t("vChildrenMin"), [childInput]));
        }
        if (!sourceValue()) {
            add(t("vSource"), sourceSelect, () => setError("source", t("vSource"), [sourceSelect]));
        } else if (isOtaSource() && !otaInput.value.trim()) {
            add(t("vOta"), otaInput, () => setError("otaBookingReference", t("vOta"), [otaInput]));
        }

        if (rows.every((row) => !row.roomId)) {
            add(t("vRooms"), rows[0].trigger, () => {
                if (roomsError) roomsError.textContent = t("vRooms");
                rows[0].trigger.setAttribute("aria-invalid", "true");
            });
        }
        const seen = new Set();
        rows.forEach((row, index) => {
            const anyRoomChosen = rows.some((candidate) => candidate.roomId);
            if (!row.roomId && anyRoomChosen) {
                add(rowMessage(index, t("vRoomRequired")), row.trigger, () => setRowError(row, "room", t("vRoomRequired")));
            } else if (row.roomId && seen.has(row.roomId)) {
                add(rowMessage(index, t("vRoomDuplicate")), row.trigger, () => setRowError(row, "room", t("vRoomDuplicate")));
            }
            if (row.roomId) seen.add(row.roomId);
            if (!row.roomId) return;
            const rate = model.parseRate(row.rateInput.value);
            const rateMessages = {
                empty: t("vRateRequired"),
                invalid: t("vRateInvalid"),
                fraction: t("vRateScale"),
                positive: t("vRatePositive"),
            };
            if (rate.status !== "ok") {
                add(rowMessage(index, rateMessages[rate.status]), row.rateInput,
                    () => setRowError(row, "rate", rateMessages[rate.status]));
            }
        });
        if (currentGuest && accompanyingGuests().some((guest) => guest.id === currentGuest.id)) {
            add(t("accompanyingPrimary"), byId("accompanyingSearch"),
                () => setError("accompanyingGuestIds", t("accompanyingPrimary"), [byId("accompanyingSearch")]));
        }
        if (model.notesLength(notesInput.value) > model.NOTES_MAX) {
            add(t("vNotesMax"), notesInput, () => setError("notes", t("vNotesMax"), [notesInput]));
        }
        return problems;
    };

    // After the error dialog closes: scroll to and focus the first invalid field, in page order.
    const focusFirstInvalid = () => {
        const invalid = Array.from(form.querySelectorAll('[aria-invalid="true"]'))
            .find((control) => !control.disabled && control.type !== "hidden" && control.offsetParent !== null);
        if (invalid) {
            focusControl(invalid);
            return;
        }
        if (roomsError && roomsError.textContent.trim()) focusControl(rows[0].trigger);
    };

    form.addEventListener("submit", (event) => {
        const problems = collectProblems();
        if (problems.length === 0) {
            // About to leave: nothing needs to be kept for a later visit.
            model.clearDraftState(window.sessionStorage);
            return;
        }
        // Stop before submit-guard.js disables the button and numeric-input.js strips the rate separators.
        event.preventDefault();
        event.stopImmediatePropagation();
        clearAllErrors();
        problems.forEach((problem) => problem.show());
        const dialog = window.PmsFeedbackDialog;
        if (!dialog) {
            focusFirstInvalid();
            return;
        }
        dialog.show({
            title: t("errorTitle"),
            message: t("errorIntro"),
            items: problems.map((problem) => problem.message),
            onClose: () => window.setTimeout(focusFirstInvalid, 0),
        });
    }, true);

    // The server rejected the submit: its dialog (already open) lists the problems; focus the first invalid field once
    // it is closed.
    const serverDialog = byId("feedback-dialog");
    const serverList = serverDialog && serverDialog.querySelector("#feedback-dialog-list");
    if (serverDialog && serverList && !serverList.hidden && serverList.children.length > 0) {
        serverDialog.addEventListener("close", () => window.setTimeout(focusFirstInvalid, 0), { once: true });
    }

    // A field stops being marked once it is edited.
    const clearOnEdit = (event) => {
        const target = event.target;
        if (!(target instanceof Element) || !target.hasAttribute("aria-invalid")) return;
        target.removeAttribute("aria-invalid");
        const holder = target.closest(".form-field, td");
        const message = holder && holder.querySelector(".field-error");
        if (message) message.textContent = "";
    };
    form.addEventListener("input", clearOnEdit);
    form.addEventListener("change", clearOnEdit);

    // ---------------------------------------------------------------------------------------------- Cancel / Create New Guest

    const discardForm = document.querySelector("[data-discard-form]");
    if (discardForm) {
        // Capture phase, before confirmation.js: with nothing meaningful entered Cancel leaves straight away; otherwise
        // the shared "Discard changes?" dialog opens.
        discardForm.addEventListener("submit", (event) => {
            const pristine = model.isPristine(collectState(), defaults);
            model.clearDraftState(window.sessionStorage);
            if (pristine) event.stopImmediatePropagation();
        }, true);
    }

    const newGuestLink = form.querySelector("[data-create-guest-link]");
    newGuestLink.addEventListener("click", (event) => {
        if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        const state = collectState();
        // Keep only unsaved input, only for the round trip; a pristine form has nothing worth keeping.
        if (!model.isPristine(state, defaults)) model.saveDraftState(window.sessionStorage, state, Date.now());
    });

    // Back / forward cache: a page restored after a successful submit must not stay locked.
    window.addEventListener("pageshow", (event) => {
        if (!event.persisted || !saveButton) return;
        saveButton.disabled = false;
        saveButton.removeAttribute("aria-busy");
    });

    // ---------------------------------------------------------------------------------------------- start up

    initialize();

    function initialize() {
        // Initial values from the server (empty form, a rejected submit, or the Guest just created).
        currentGuest = guestFromDataset((form.querySelector("[data-initial-guest]") || { dataset: {} }).dataset);
        renderGuestCard();

        form.querySelectorAll("[data-initial-room]").forEach((span) => {
            const data = span.dataset;
            const row = addRow({
                roomId: data.roomId,
                roomNumber: data.roomNumber,
                rate: (data.rate || "").replace(/\.0+$/, ""),
            });
            setRowError(row, "room", data.errorRoom);
            setRowError(row, "rate", data.errorRate);
        });
        if (rows.length === 0) addRow(null);

        const createdGuestId = config.createdGuestId || "";
        const referrerPath = (() => {
            try {
                return document.referrer ? new URL(document.referrer).pathname : "";
            } catch (ignored) {
                return "";
            }
        })();
        const navigation = window.performance && window.performance.getEntriesByType
            ? window.performance.getEntriesByType("navigation")[0] : null;
        const restored = model.resolveRestore(window.sessionStorage, Date.now(), {
            createdGuestId,
            cameFromGuestCreation: referrerPath === "/guests/new" || referrerPath === "/guests",
            navigationType: navigation ? navigation.type : "navigate",
        });
        if (restored) applyState(restored);
        // The Guest just created is the Primary Guest; the server rendered its details into the page.
        const serverGuest = guestFromDataset((form.querySelector("[data-initial-guest]") || { dataset: {} }).dataset);
        if (createdGuestId && serverGuest) setPrimaryGuest(serverGuest);

        updateOtaField();
        checkAccompanyingConflict();
        render();
        loadAvailability();
    }

    sourceSelect.addEventListener("change", () => {
        updateOtaField();
        render();
    });
    [adultInput, childInput, otaInput, notesInput, ...contactInputs].forEach((input) => {
        input.addEventListener("input", render);
    });
})();
