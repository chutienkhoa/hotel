(() => {
    const calendarSelector = ".js-stay-extension-calendar";
    const liveRegions = ["selection", "availability", "rates", "summary", "actions"];
    const dayMs = 86400000;

    // Whole-day arithmetic in UTC, so a date string never shifts with the browser's timezone.
    const toDayNumber = (iso) => {
        const [year, month, day] = iso.split("-").map(Number);
        return Date.UTC(year, month - 1, day) / dayMs;
    };
    const toIso = (dayNumber) => new Date(dayNumber * dayMs).toISOString().slice(0, 10);
    const toDisplayDate = (dayNumber) => {
        const [year, month, day] = toIso(dayNumber).split("-");
        return `${day}/${month}/${year}`;
    };
    const toMoney = (amount) => `${amount.toLocaleString("en-US")} VND`;

    // Client estimate, shown instantly for feedback. It mirrors the server arithmetic (nights x nightly rate) and is
    // replaced by the server's figures as soon as the authoritative response is applied.
    const renderEstimate = (input, iso) => {
        const currentDay = toDayNumber(input.dataset.currentCheckout);
        const selectedDay = toDayNumber(iso);
        const nights = selectedDay - currentDay;
        const ratesTotal = [...document.querySelectorAll("[data-nightly-rate]")]
            .reduce((sum, row) => sum + Number(row.dataset.nightlyRate), 0);
        const unit = nights === 1 ? input.dataset.unitOne : input.dataset.unitOther;

        document.getElementById("stay-extension-selected-date").textContent = toDisplayDate(selectedDay);
        const panel = document.getElementById("stay-extension-nights-panel");
        panel.querySelector(".stay-extension-nights__value > span").textContent = nights;
        panel.querySelector(".stay-extension-nights__unit").textContent = unit;
        let range = panel.querySelector(".stay-extension-nights__range");
        if (!range) {
            range = document.createElement("span");
            range.className = "stay-extension-nights__range";
            panel.append(range);
        }
        range.textContent = `${toDisplayDate(currentDay)} – ${toDisplayDate(selectedDay - 1)}`;
        document.getElementById("stay-extension-additional-nights").textContent = nights;
        document.querySelector("[data-live=\"rates\"] .stay-extension-unit").textContent = unit;
        document.getElementById("stay-extension-charge-preview").textContent = toMoney(nights * ratesTotal);
    };

    // Pending: the selection is shown immediately, but Next stays disabled until the server confirms it.
    const setPending = (checkingText) => {
        const state = document.getElementById("stay-extension-state");
        state.classList.add("stay-extension-state--pending");
        state.querySelector(".stay-extension-state__text").textContent = checkingText;
        document.getElementById("stay-extension-next").disabled = true;
        document.querySelector("[data-live=\"actions\"]").setAttribute("aria-busy", "true");
    };

    const applyServerState = (fresh) => {
        liveRegions.forEach((name) => {
            const current = document.querySelector(`[data-live="${name}"]`);
            current.replaceWith(document.importNode(fresh.querySelector(`[data-live="${name}"]`), true));
        });
        document.querySelector("[data-live=\"actions\"]").removeAttribute("aria-busy");
    };

    const showFailure = (input) => {
        const state = document.getElementById("stay-extension-state");
        state.classList.remove("stay-extension-state--pending");
        state.querySelector(".stay-extension-state__text").textContent = input.dataset.checkFailedText;
        document.getElementById("stay-extension-next").disabled = true;
        document.querySelector("[data-live=\"actions\"]").removeAttribute("aria-busy");
    };

    // Only the latest selection may change the page. Each change aborts the previous request and bumps the sequence,
    // so an older response that arrives late is discarded, and a response is applied only when it echoes the date
    // that is still selected.
    const createSelectionHandler = (input) => {
        let latest = 0;
        let inFlight = null;

        return (iso) => {
            const sequence = ++latest;
            renderEstimate(input, iso);
            setPending(input.dataset.checkingText);
            if (inFlight) {
                inFlight.abort();
            }
            const controller = new AbortController();
            inFlight = controller;

            const url = new URL(window.location.href);
            url.searchParams.set("newCheckOutDate", iso);
            fetch(url.toString(), { credentials: "same-origin", headers: { Accept: "text/html" }, signal: controller.signal })
                .then((response) => {
                    if (!response.ok || response.redirected) {
                        throw new Error("stay-extension-unavailable");
                    }
                    return response.text();
                })
                .then((html) => {
                    if (sequence !== latest) {
                        return;
                    }
                    const fresh = new DOMParser().parseFromString(html, "text/html");
                    const echoed = fresh.getElementById("newCheckOutDate");
                    if (!echoed || echoed.getAttribute("value") !== iso) {
                        throw new Error("stay-extension-mismatch");
                    }
                    applyServerState(fresh);
                    window.history.replaceState(null, "", url.toString());
                })
                .catch((error) => {
                    if (error.name === "AbortError" || sequence !== latest) {
                        return;
                    }
                    showFailure(input);
                });
        };
    };

    const pad = (value) => String(value).padStart(2, "0");
    const toLocalIso = (date) => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;

    // Calendar states come from the server's availability window: the current checkout, the free dates before the first
    // conflict, and the conflicting dates from the first conflict on. Dates outside the window carry no state.
    const dayState = (input, iso) => {
        const current = input.dataset.currentCheckout;
        const knownUntil = input.dataset.availableUntil;
        const firstUnavailable = input.dataset.firstUnavailable;
        if (iso === current) {
            return "current";
        }
        if (iso <= current || iso > knownUntil) {
            return null;
        }
        if (firstUnavailable && iso >= firstUnavailable) {
            return "unavailable";
        }
        return "available";
    };

    const initializeCalendar = (input) => {
        const form = input.form;
        const container = input.closest(".stay-extension-calendar");
        if (!form || !container || typeof window.flatpickr !== "function") {
            return;
        }

        const handleSelection = createSelectionHandler(input);
        // flatpickr will not mark a disabled date as selected, so the server's requested date is marked explicitly.
        // The selected date drives the extension-night highlight, so it follows each selection; the server's requested date
        // seeds it.
        let selectedIso = input.value;
        input.type = "text";
        window.flatpickr(input, {
            inline: true,
            dateFormat: "Y-m-d",
            defaultDate: input.value || null,
            minDate: input.dataset.minDate || null,
            disableMobile: true,
            // Conflicting dates cannot be chosen at all, not merely styled.
            disable: [(date) => dayState(input, toLocalIso(date)) === "unavailable"],
            onDayCreate: (_selectedDates, _dateString, _instance, dayElement) => {
                const iso = toLocalIso(dayElement.dateObj);
                const state = dayState(input, iso);
                if (state) {
                    dayElement.classList.add(`stay-extension-day--${state}`);
                }
                if (iso === selectedIso) {
                    dayElement.classList.add("stay-extension-day--requested");
                }
                // Selected extension nights start the day after the current checkout and end before the selected date. The
                // current checkout itself never takes this state; unavailable dates keep their gray state inside the span.
                const current = input.dataset.currentCheckout;
                if (iso > current && iso < selectedIso && state !== "unavailable") {
                    dayElement.classList.add("stay-extension-day--extension");
                }
            },
            onChange: (_selectedDates, dateString, instance) => {
                selectedIso = dateString;
                handleSelection(dateString);
                instance.redraw();
            }
        });
        container.classList.add("stay-extension-calendar--enhanced");
    };

    const initializeCalendars = () => {
        document.querySelectorAll(calendarSelector).forEach(initializeCalendar);
    };

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initializeCalendars);
    } else {
        initializeCalendars();
    }
})();
