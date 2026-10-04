// Shared Stay date-range picker: one control that opens a popover with Check-in / Check-out fields, a two-month calendar
// (one month on a narrow screen), Clear and Apply. The first date clicked is the check-in, the second the check-out, and
// the whole range is highlighted. Apply is enabled only for a complete range that ends on a later day than it starts
// (the range is half-open, so a same-day range is empty). Used by the Reservation List Stay filter and Create
// Reservation; each page supplies what happens after Apply / Clear and keeps its own validation (the server validates
// again). Markup: see the [data-stay-picker] block in reservation/list.html and reservation/create.html.
(function () {
    "use strict";

    // Vietnamese weekday and month names for the calendar; English is flatpickr's default.
    var VIETNAMESE = {
        firstDayOfWeek: 1,
        weekdays: {
            shorthand: ["CN", "T2", "T3", "T4", "T5", "T6", "T7"],
            longhand: ["Chủ nhật", "Thứ hai", "Thứ ba", "Thứ tư", "Thứ năm", "Thứ sáu", "Thứ bảy"]
        },
        months: {
            shorthand: ["Th1", "Th2", "Th3", "Th4", "Th5", "Th6", "Th7", "Th8", "Th9", "Th10", "Th11", "Th12"],
            longhand: ["Tháng 1", "Tháng 2", "Tháng 3", "Tháng 4", "Tháng 5", "Tháng 6", "Tháng 7", "Tháng 8", "Tháng 9", "Tháng 10", "Tháng 11", "Tháng 12"]
        }
    };

    function pad(number) {
        return (number < 10 ? "0" : "") + number;
    }

    function iso(date) {
        return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate());
    }

    function display(date) {
        return pad(date.getDate()) + "/" + pad(date.getMonth() + 1) + "/" + date.getFullYear();
    }

    function parseIso(value) {
        var parts = value.split("-");
        return new Date(Number(parts[0]), Number(parts[1]) - 1, Number(parts[2]));
    }

    /**
     * Wires one picker.
     *
     * @param root the [data-stay-picker] element
     * @param options.onApply called with the ISO check-in and check-out after Apply
     * @param options.onClear called after Clear with whether a range had been applied
     * @returns {{setRange: function(string, string), trigger: Element}|null} null when flatpickr or the markup is missing
     */
    function init(root, options) {
        if (!root || typeof window.flatpickr !== "function") {
            return null;
        }
        var settings = options || {};
        var fromField = root.querySelector("[data-stay-from]");
        var toField = root.querySelector("[data-stay-to]");
        var trigger = root.querySelector("[data-stay-trigger]");
        var summary = root.querySelector("[data-stay-summary]");
        var popover = root.querySelector("[data-stay-popover]");
        var inDisplay = root.querySelector("[data-stay-in-display]");
        var outDisplay = root.querySelector("[data-stay-out-display]");
        var calendarHost = root.querySelector("[data-stay-calendar]");
        var hint = root.querySelector("[data-stay-hint]");
        var applyButton = root.querySelector("[data-stay-apply]");
        var clearButton = root.querySelector("[data-stay-clear]");
        var emptyText = root.getAttribute("data-all-dates") || "";

        // The calendar is built on first open, while the popover is visible: flatpickr measures its month width at
        // build time, which is zero inside a hidden container.
        var picker = null;

        function appliedRange() {
            return fromField.value && toField.value ? [fromField.value, toField.value] : [];
        }

        function ensurePicker() {
            if (picker) {
                return picker;
            }
            var calendarInput = document.createElement("input");
            calendarInput.type = "hidden";
            calendarHost.appendChild(calendarInput);
            picker = window.flatpickr(calendarInput, {
                inline: true,
                mode: "range",
                dateFormat: "Y-m-d",
                showMonths: window.matchMedia("(min-width: 40rem)").matches ? 2 : 1,
                locale: document.documentElement.lang === "vi" ? VIETNAMESE : { firstDayOfWeek: 1 },
                defaultDate: appliedRange(),
                onChange: function (dates) {
                    refresh(dates);
                }
            });
            return picker;
        }

        // Mirrors the calendar selection into the Check-in / Check-out fields and enables Apply only for a complete,
        // ordered range.
        function refresh(dates) {
            inDisplay.value = dates.length > 0 ? display(dates[0]) : "";
            outDisplay.value = dates.length > 1 ? display(dates[1]) : "";
            var complete = dates.length === 2;
            var ordered = complete && iso(dates[1]) > iso(dates[0]);
            applyButton.disabled = !ordered;
            hint.hidden = !(complete && !ordered);
        }

        function setSummary(from, to) {
            if (from && to) {
                summary.textContent = display(parseIso(from)) + " → " + display(parseIso(to));
                summary.classList.remove("reservation-stay__summary--empty");
            } else {
                summary.textContent = emptyText;
                summary.classList.add("reservation-stay__summary--empty");
            }
        }

        function open() {
            popover.hidden = false;
            trigger.setAttribute("aria-expanded", "true");
            var calendar = ensurePicker();
            calendar.setDate(appliedRange(), false);
            refresh(calendar.selectedDates);
        }

        function close(returnFocus) {
            popover.hidden = true;
            trigger.setAttribute("aria-expanded", "false");
            if (returnFocus) {
                trigger.focus();
            }
        }

        trigger.addEventListener("click", function () {
            if (popover.hidden) {
                open();
            } else {
                close(false);
            }
        });

        applyButton.addEventListener("click", function () {
            var dates = picker ? picker.selectedDates : [];
            if (dates.length !== 2 || iso(dates[1]) <= iso(dates[0])) {
                return;
            }
            fromField.value = iso(dates[0]);
            toField.value = iso(dates[1]);
            setSummary(fromField.value, toField.value);
            close(true);
            if (settings.onApply) {
                settings.onApply(fromField.value, toField.value);
            }
        });

        clearButton.addEventListener("click", function () {
            var hadRange = Boolean(fromField.value || toField.value);
            if (picker) {
                picker.clear(false);
            }
            fromField.value = "";
            toField.value = "";
            setSummary("", "");
            refresh([]);
            close(true);
            if (settings.onClear) {
                settings.onClear(hadRange);
            }
        });

        document.addEventListener("click", function (event) {
            // composedPath is read at dispatch time, so a calendar element that flatpickr re-renders mid-click still
            // counts.
            if (!popover.hidden && event.composedPath().indexOf(root) === -1) {
                close(false);
            }
        });

        root.addEventListener("keydown", function (event) {
            if (event.key === "Escape" && !popover.hidden) {
                event.stopPropagation();
                close(true);
            }
        });

        setSummary(fromField.value, toField.value);

        return {
            trigger: trigger,
            // Sets the applied range programmatically (for example when a page restores saved input).
            setRange: function (from, to) {
                fromField.value = from || "";
                toField.value = to || "";
                setSummary(fromField.value, toField.value);
                if (picker) {
                    picker.setDate(appliedRange(), false);
                }
            }
        };
    }

    window.PmsStayRangePicker = { init: init };
})();
