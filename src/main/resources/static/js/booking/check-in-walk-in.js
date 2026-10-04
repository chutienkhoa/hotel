(() => {
    // Presentation behaviour for the Walk-in Reservation form and, with an editable check-in date, the OTA Booking
    // Not Entered form. Which rooms are offered, whether they fit the adults and every date rule stay on the server
    // (the available-rooms endpoint named by data-availability-url, default the Walk-in one, and the review step);
    // nothing here decides a business outcome.
    const form = document.getElementById("walk-in-form");
    const roomsSection = document.getElementById("walk-in-rooms");
    const checkOutInput = document.getElementById("checkOutDate");
    if (!form || !roomsSection || !checkOutInput) return;

    const rowsBody = roomsSection.querySelector("[data-room-rows]");
    const tableWrap = roomsSection.querySelector("[data-room-table-wrap]");
    const stateText = roomsSection.querySelector("[data-room-state]");
    const typeFilter = document.getElementById("roomTypeFilter");
    const capacitySummary = roomsSection.querySelector("[data-capacity-summary]");
    const adultInput = document.getElementById("adultCount");
    const guestSelect = document.getElementById("guestId");
    const nightsOutput = document.querySelector("[data-nights]");
    // Walk-in: the fixed hotel date. OTA: the operator-entered check-in date input.
    const checkInInput = document.getElementById("checkInDate");
    const fixedCheckIn = document.querySelector("[data-check-in]")?.dataset.checkInDate || "";
    const currentCheckIn = () => (checkInInput ? checkInInput.value : fixedCheckIn);
    const steps = document.querySelector("[data-walk-in-steps]");
    const labels = roomsSection.dataset;
    const checkButton = roomsSection.querySelector("[data-check-availability]");
    const guestsContext = roomsSection.querySelector("[data-guests-context]");
    const rangeLine = roomsSection.querySelector("[data-room-range]");
    const childInput = document.getElementById("childCount");
    const language = document.documentElement.lang || "en";

    // Selected rooms by id -> nightly rate text. It is the single source for what is checked, so the selection
    // survives a reload of the room list (for example after the check-out date changes).
    const selection = new Map();
    // Rooms whose rate the server rejected on the last submit; they are shown as invalid until the rate is edited.
    const invalidRates = new Set();
    roomsSection.querySelectorAll("[data-selected-room]").forEach((preserved) => {
        const rate = (preserved.dataset.rate || "").replace(/\.0+$/, "");
        selection.set(preserved.dataset.roomId, rate);
        if (preserved.dataset.rateInvalid === "true") invalidRates.add(preserved.dataset.roomId);
    });

    let options = [];
    let requestId = 0;

    const parseDate = (iso) => {
        const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || "");
        return match ? Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])) : null;
    };

    const nights = () => {
        const from = parseDate(currentCheckIn());
        const to = parseDate(checkOutInput.value);
        if (from === null || to === null || to <= from) return null;
        return Math.round((to - from) / 86400000);
    };

    const showState = (message) => {
        stateText.textContent = message || "";
        stateText.hidden = !message;
        tableWrap.hidden = Boolean(message) || options.length === 0;
    };

    const updateSteps = () => {
        if (!steps) return;
        const complete = {
            guest: Boolean(guestSelect && guestSelect.value),
            stay: Boolean(checkOutInput.value) && (!checkInInput || Boolean(checkInInput.value)),
            room: selection.size > 0,
            summary: false,
        };
        let current = null;
        steps.querySelectorAll("[data-step]").forEach((step) => {
            const done = complete[step.dataset.step];
            step.classList.toggle("walk-in-step--complete", done);
            step.classList.remove("walk-in-step--current");
            step.removeAttribute("aria-current");
            if (!done && current === null) {
                current = step;
            }
        });
        if (current) {
            current.classList.add("walk-in-step--current");
            current.setAttribute("aria-current", "step");
        }
    };

    const updateCapacitySummary = () => {
        const chosen = options.filter((room) => selection.has(room.id));
        if (chosen.length === 0) {
            capacitySummary.hidden = true;
            return;
        }
        const capacity = chosen.reduce((sum, room) => sum + (room.adultCapacity || 0), 0);
        const adults = Number.parseInt(adultInput?.value, 10);
        capacitySummary.textContent = (labels.msgCapacity || "")
            .replace("{0}", String(capacity))
            .replace("{1}", Number.isNaN(adults) ? "—" : String(adults));
        capacitySummary.classList.toggle("walk-in-capacity--short", !Number.isNaN(adults) && adults > capacity);
        capacitySummary.hidden = false;
    };

    // Form field names follow the checked rows in document order, so the server receives a dense rooms[i] list
    // with exactly the selected rooms and their rates.
    const updateFieldNames = () => {
        let index = 0;
        rowsBody.querySelectorAll("tr").forEach((row) => {
            const checkbox = row.querySelector("input[type=checkbox]");
            const rate = row.querySelector("input[data-nightly-rate]");
            if (checkbox.checked) {
                checkbox.name = `rooms[${index}].roomId`;
                rate.name = `rooms[${index}].nightlyRate`;
                rate.disabled = false;
                index += 1;
            } else {
                checkbox.removeAttribute("name");
                rate.removeAttribute("name");
                rate.disabled = true;
            }
        });
        updateCapacitySummary();
        updateSteps();
    };

    const applyTypeFilter = () => {
        const type = typeFilter.value;
        rowsBody.querySelectorAll("tr").forEach((row) => {
            // A checked room is never hidden by the filter, so nothing selected can be out of sight.
            row.hidden = Boolean(type) && row.dataset.roomType !== type && !row.querySelector("input[type=checkbox]").checked;
        });
    };

    const cell = (text, className) => {
        const td = document.createElement("td");
        td.textContent = text;
        if (className) td.className = className;
        return td;
    };

    // The room number links to the existing Room Detail page (same tab) only when the server supplied the route,
    // which it does only for users allowed to open it; otherwise it stays plain text.
    const roomNumberCell = (room) => {
        const td = cell(room.roomNumber, "walk-in-room-number");
        if (!labels.roomUrl) return td;
        const link = document.createElement("a");
        link.className = "record-link";
        // Opens beside the form: leaving it in this tab would drop whatever has been typed and not yet submitted.
        link.target = "_blank";
        link.rel = "noopener";
        link.href = labels.roomUrl.replace("ROOM_ID", encodeURIComponent(room.id));
        link.textContent = room.roomNumber;
        td.textContent = "";
        td.appendChild(link);
        return td;
    };

    const buildRow = (room) => {
        const row = document.createElement("tr");
        row.dataset.roomType = room.roomTypeName || "";
        row.dataset.roomNumber = room.roomNumber;

        const selectCell = document.createElement("td");
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.value = room.id;
        checkbox.id = `room-option-${room.id}`;
        checkbox.checked = selection.has(room.id);
        checkbox.setAttribute("aria-label", `${labels.labelSelect || ""} ${room.roomNumber}`.trim());
        selectCell.appendChild(checkbox);

        const status = document.createElement("span");
        status.className = "status-badge status-badge--available";
        status.textContent = labels.msgAvailable || room.status;
        const statusCell = document.createElement("td");
        statusCell.appendChild(status);

        const rateCell = document.createElement("td");
        rateCell.className = "walk-in-num";
        const rate = document.createElement("input");
        rate.className = "js-money-input walk-in-rate";
        rate.type = "text";
        rate.inputMode = "decimal";
        rate.dataset.nightlyRate = "";
        rate.setAttribute("aria-label", (labels.labelRate || "").replace("{0}", room.roomNumber));
        rate.value = selection.get(room.id) || "";
        if (invalidRates.has(room.id)) rate.setAttribute("aria-invalid", "true");
        rateCell.appendChild(rate);

        row.append(
            selectCell,
            roomNumberCell(room),
            cell(room.roomTypeName || "—"),
            cell(room.adultCapacity == null ? "—" : String(room.adultCapacity), "walk-in-num"),
            statusCell,
            rateCell);

        checkbox.addEventListener("change", () => {
            if (checkbox.checked) {
                selection.set(room.id, rate.value);
                rate.focus();
            } else {
                selection.delete(room.id);
            }
            updateFieldNames();
            applyTypeFilter();
        });
        rate.addEventListener("input", () => {
            selection.set(room.id, rate.value);
            invalidRates.delete(room.id);
            rate.removeAttribute("aria-invalid");
        });
        row.addEventListener("click", (event) => {
            if (event.target.closest("input, a")) return;
            checkbox.click();
        });
        if (window.PmsMoneyInput) window.PmsMoneyInput.initialize(rate);
        return row;
    };

    const renderRooms = () => {
        // Forget a selection that is no longer offered; the server rejects it anyway, and it must not be submitted silently.
        const offered = new Set(options.map((room) => room.id));
        Array.from(selection.keys()).forEach((id) => {
            if (!offered.has(id)) selection.delete(id);
        });

        const types = Array.from(new Set(options.map((room) => room.roomTypeName).filter(Boolean))).sort();
        const previousType = typeFilter.value;
        typeFilter.length = 1;
        types.forEach((type) => typeFilter.add(new Option(type, type)));
        typeFilter.value = types.includes(previousType) ? previousType : "";
        typeFilter.disabled = types.length === 0;

        rowsBody.replaceChildren(...options.map(buildRow));
        showState(options.length === 0 ? labels.msgEmpty : "");
        updateFieldNames();
        applyTypeFilter();
    };

    const loadRooms = () => {
        const checkOut = checkOutInput.value;
        const checkIn = currentCheckIn();
        const ready = Boolean(checkOut) && (!checkInInput || (Boolean(checkIn) && nights() !== null));
        const current = ++requestId;
        checkButton.disabled = !ready;
        if (!ready) {
            options = [];
            rowsBody.replaceChildren();
            typeFilter.length = 1;
            typeFilter.disabled = true;
            showState(labels.msgPrompt);
            updateFieldNames();
            return;
        }
        showState(labels.msgLoading);
        const query = checkInInput
            ? `checkInDate=${encodeURIComponent(checkIn)}&checkOutDate=${encodeURIComponent(checkOut)}`
            : `checkOutDate=${encodeURIComponent(checkOut)}`;
        fetch(`${labels.availabilityUrl || "/check-in/walk-in/available-rooms"}?${query}`, {
            headers: { Accept: "application/json" },
        })
            .then((response) => {
                if (!response.ok) throw new Error(String(response.status));
                return response.json();
            })
            .then((rooms) => {
                if (current !== requestId) return;
                options = rooms;
                renderRooms();
            })
            .catch(() => {
                if (current !== requestId) return;
                options = [];
                rowsBody.replaceChildren();
                showState(labels.msgError);
                updateFieldNames();
            });
    };

    // Read-only context above the room table: the guests the rooms are for, and the stay range being searched.
    const updateGuestsContext = () => {
        const adults = Number.parseInt(adultInput?.value, 10);
        const children = Number.parseInt(childInput?.value, 10);
        guestsContext.textContent = (guestsContext.dataset.msgGuests || "")
            .replace("{0}", Number.isNaN(adults) ? "—" : String(adults))
            .replace("{1}", Number.isNaN(children) ? "—" : String(children));
    };

    const formatDay = (iso) => {
        const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || "");
        if (!match) return "";
        const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
        return new Intl.DateTimeFormat(language, { weekday: "short", day: "2-digit", month: "short", year: "numeric" }).format(date);
    };

    const updateRange = () => {
        const count = nights();
        rangeLine.hidden = count === null;
        if (count === null) return;
        const template = count === 1 ? rangeLine.dataset.msgNight : rangeLine.dataset.msgNights;
        rangeLine.querySelector("[data-room-range-text]").textContent =
            `${formatDay(currentCheckIn())} – ${formatDay(checkOutInput.value)} (${(template || "{0}").replace("{0}", String(count))})`;
    };

    const onCheckOutChange = () => {
        const count = nights();
        nightsOutput.textContent = count === null ? "—" : String(count);
        updateRange();
        loadRooms();
    };

    // Guest Information: details of the selected Guest come from the Guest record (the option's data attributes);
    // nothing here is editable or submitted. Search Existing Guest filters the guests already loaded into the selector.
    const guestInfo = document.querySelector("[data-guest-information]");
    const guestEmpty = document.querySelector("[data-guest-empty]");
    const searchToggle = document.querySelector("[data-guest-search-toggle]");
    const searchPanel = document.querySelector("[data-guest-search]");
    const searchQuery = searchPanel?.querySelector("[data-guest-query]");
    const searchResults = searchPanel?.querySelector("[data-guest-results]");
    const searchNoResults = searchPanel?.querySelector("[data-guest-no-results]");
    const MAX_RESULTS = 8;

    const showGuest = () => {
        if (!guestSelect || !guestInfo) return;
        const option = guestSelect.options[guestSelect.selectedIndex];
        const chosen = Boolean(option && option.value);
        guestInfo.hidden = !chosen;
        if (guestEmpty) guestEmpty.hidden = chosen;
        if (!chosen) return;
        const fill = (selector, value) => {
            guestInfo.querySelector(selector).textContent = value || "—";
        };
        fill("[data-guest-full-name]", option.dataset.fullName);
        fill("[data-guest-nationality]", option.dataset.nationality);
        // The flag is resolved on the server from the Guest's nationality (GuestNationalityDisplay); absent means none.
        const flag = guestInfo.querySelector("[data-guest-flag]");
        flag.textContent = option.dataset.nationalityFlag || "";
        flag.hidden = !option.dataset.nationalityFlag;
        fill("[data-guest-phone]", option.dataset.phone);
        fill("[data-guest-email]", option.dataset.email);
        fill("[data-guest-dob]", option.dataset.dateOfBirth);
        fill("[data-guest-id-document]", option.dataset.idDocumentNumber);
        fill("[data-guest-code]", option.dataset.guestCode);
    };

    const guestMatches = (option, text) =>
        [option.dataset.guestCode, option.dataset.fullName, option.dataset.phone, option.dataset.email, option.dataset.idDocumentNumber]
            .some((value) => (value || "").toLowerCase().includes(text));

    const renderGuestResults = () => {
        const text = searchQuery.value.trim().toLowerCase();
        const matches = Array.from(guestSelect.options)
            .filter((option) => option.value && (!text || guestMatches(option, text)))
            .slice(0, MAX_RESULTS);
        searchResults.replaceChildren(...matches.map((option) => {
            const item = document.createElement("li");
            const button = document.createElement("button");
            button.type = "button";
            button.className = "walk-in-guest-result";
            const name = document.createElement("span");
            name.className = "walk-in-guest-result__name";
            name.textContent = `${option.dataset.guestCode} · ${option.dataset.fullName}`;
            const detail = document.createElement("span");
            detail.className = "walk-in-guest-result__detail";
            detail.textContent = [option.dataset.phone, option.dataset.email].filter(Boolean).join(" · ");
            button.append(name, detail);
            button.addEventListener("click", () => {
                guestSelect.value = option.value;
                guestSelect.dispatchEvent(new Event("change", { bubbles: true }));
                closeGuestSearch(true);
            });
            item.appendChild(button);
            return item;
        }));
        searchNoResults.hidden = matches.length > 0;
    };

    function closeGuestSearch(returnFocus) {
        searchPanel.hidden = true;
        searchToggle.setAttribute("aria-expanded", "false");
        if (returnFocus) searchToggle.focus();
    }

    if (searchToggle && searchPanel && searchQuery) {
        searchToggle.addEventListener("click", () => {
            if (!searchPanel.hidden) {
                closeGuestSearch(false);
                return;
            }
            searchPanel.hidden = false;
            searchToggle.setAttribute("aria-expanded", "true");
            renderGuestResults();
            searchQuery.focus();
        });
        searchQuery.addEventListener("input", renderGuestResults);
        searchQuery.addEventListener("keydown", (event) => {
            if (event.key === "Escape") {
                event.preventDefault();
                closeGuestSearch(true);
            } else if (event.key === "Enter") {
                // Enter inside the search box picks the first match; it must never submit the Walk-in form.
                event.preventDefault();
                searchResults.querySelector("button")?.click();
            }
        });
    }
    guestSelect?.addEventListener("change", showGuest);

    checkButton?.addEventListener("click", loadRooms);

    // Required-field check on "Next: Reservation Summary". Every empty required field is collected (not just the
    // first), marked invalid and listed in the shared feedback dialog; the form is not submitted. This is usability
    // only: the server validates everything again and rejects the same fields regardless. Field names come from the
    // same checkin.validation.field.* messages the server uses for its own dialog (data-validation-labels).
    const validationLabels = document.querySelector("[data-validation-labels]")?.dataset || {};
    // OTA only: the Walk-in source list is display-only text (no inputs), so there is nothing to require there.
    const sourceInputs = Array.from(form.querySelectorAll("input[name=source]"));
    const sourceList = sourceInputs[0]?.closest("[role=radiogroup]");
    const referenceInput = document.getElementById("otaBookingReference");
    // flatpickr shows an alternate input for each date field and keeps the original hidden.
    const visibleControl = (input) => input?._flatpickr?.altInput || input;
    const isBlank = (input) => !input || input.value.trim() === "";

    const collectMissing = () => {
        const missing = [];
        const add = (labelKey, marks, focus, detail) => missing.push({ labelKey, marks, focus, detail });
        if (guestSelect && isBlank(guestSelect)) add("guestId", [guestSelect], guestSelect);
        if (checkInInput && isBlank(checkInInput)) add("checkInDate", [checkInInput], visibleControl(checkInInput));
        if (isBlank(checkOutInput)) add("checkOutDate", [checkOutInput], visibleControl(checkOutInput));
        if (adultInput && isBlank(adultInput)) add("adultCount", [adultInput], adultInput);
        if (childInput && isBlank(childInput)) add("childCount", [childInput], childInput);
        if (sourceList && !sourceInputs.some((input) => input.checked)) add("source", [sourceList], sourceInputs[0]);
        if (referenceInput && isBlank(referenceInput)) add("otaBookingReference", [referenceInput], referenceInput);
        if (selection.size === 0) {
            add("rooms", [roomsSection], rowsBody.querySelector("input[type=checkbox]") || checkButton);
        }
        // One entry per selected room whose rate is empty, named by Room No.
        Array.from(rowsBody.querySelectorAll("tr")).forEach((row) => {
            if (!row.querySelector("input[type=checkbox]").checked) return;
            const rate = row.querySelector("input[data-nightly-rate]");
            if (isBlank(rate)) add("nightlyRate", [rate], rate, row.dataset.roomNumber);
        });
        return missing;
    };

    const markInvalid = (element) => {
        if (element === roomsSection) {
            roomsSection.dataset.invalid = "true";
        } else {
            element.setAttribute("aria-invalid", "true");
        }
    };

    const focusControl = (control) => {
        if (!control || control.disabled) return;
        control.scrollIntoView({ block: "center" });
        control.focus();
    };

    const showMissing = (missing) => {
        missing.forEach((entry) => entry.marks.forEach(markInvalid));
        const dialog = window.PmsFeedbackDialog;
        const restoreFocus = () => focusControl(missing[0].focus);
        if (!dialog) {
            restoreFocus();
            return;
        }
        dialog.show({
            title: validationLabels.title,
            message: validationLabels.message,
            items: missing.map((entry) => {
                const name = validationLabels[entry.labelKey] || entry.labelKey;
                return entry.detail ? `${name} — ${entry.detail}` : name;
            }),
            onClose: restoreFocus,
        });
    };

    form.addEventListener("submit", (event) => {
        // Create New Guest also submits this form (to another action) and must keep working with fields still empty.
        if (event.submitter?.hasAttribute("formaction")) return;
        const missing = collectMissing();
        if (missing.length === 0) return;
        event.preventDefault();
        // Keep the money-input script's submit normalisation from reformatting the entries we are keeping on screen.
        event.stopImmediatePropagation();
        showMissing(missing);
    }, true);

    // A field stops being marked once the operator edits it.
    const clearInvalid = (event) => {
        const target = event.target;
        if (!(target instanceof Element)) return;
        target.removeAttribute("aria-invalid");
        if (target.name === "source" && sourceList) sourceList.removeAttribute("aria-invalid");
        if (target.closest("#walk-in-rooms")) delete roomsSection.dataset.invalid;
    };
    form.addEventListener("input", clearInvalid);
    form.addEventListener("change", clearInvalid);

    // The server rejected required fields: its feedback dialog (already open) lists them. Once it is closed, focus the
    // first marked field in form order.
    const serverDialog = document.getElementById("feedback-dialog");
    const serverList = serverDialog?.querySelector("#feedback-dialog-list");
    if (serverDialog && serverList && !serverList.hidden) {
        serverDialog.addEventListener("close", () => {
            const candidates = [
                guestSelect, visibleControl(checkInInput), visibleControl(checkOutInput), adultInput, childInput,
                sourceInputs[0], referenceInput,
                ...(roomsSection.dataset.invalid === "true" ? [rowsBody.querySelector("input[type=checkbox]") || checkButton] : []),
                ...rowsBody.querySelectorAll("input[data-nightly-rate]"),
            ];
            const invalidOf = (control) => control?.getAttribute("aria-invalid") === "true"
                || control?.closest("[role=radiogroup]")?.getAttribute("aria-invalid") === "true"
                || (control?.type === "checkbox" && roomsSection.dataset.invalid === "true");
            focusControl(candidates.find(invalidOf));
        }, { once: true });
    }

    // A stay is at least one night, so the check-out picker starts the day after check-in (the hotel date for a
    // Walk-in, the entered date for OTA). The server still decides.
    const restrictCheckOut = () => {
        const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(currentCheckIn());
        const picker = checkOutInput._flatpickr;
        if (match && picker) {
            picker.set("minDate", new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]) + 1));
        }
    };

    typeFilter.addEventListener("change", applyTypeFilter);
    checkOutInput.addEventListener("change", onCheckOutChange);
    checkInInput?.addEventListener("change", () => {
        restrictCheckOut();
        onCheckOutChange();
    });
    adultInput?.addEventListener("input", updateCapacitySummary);
    adultInput?.addEventListener("input", updateGuestsContext);
    childInput?.addEventListener("input", updateGuestsContext);
    guestSelect?.addEventListener("change", updateSteps);

    restrictCheckOut();
    const initialNights = nights();
    nightsOutput.textContent = initialNights === null ? "—" : String(initialNights);
    updateGuestsContext();
    updateRange();
    showGuest();
    updateSteps();
    loadRooms();
})();
