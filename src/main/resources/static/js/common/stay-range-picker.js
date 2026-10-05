// Shared Stay date-range picker: one control that opens a popover with Check-in / Check-out fields, a two-month calendar
// (one month on a narrow screen), Clear and Apply. The first date clicked is the check-in, the second the check-out, and
// the whole range is highlighted. Apply is enabled only for a complete range that ends on a later day than it starts
// (the range is half-open, so a same-day range is empty). Used by the Reservation List Stay filter and Create
// Reservation; each page supplies what happens after Apply / Clear and keeps its own validation (the server validates
// again). Markup: the shared layout/stay-picker fragment (Walk-in, OTA), and the [data-stay-picker] blocks in
// reservation/list.html and reservation/create.html.
//
// The popover is an overlay and is positioned on every open (and on resize) so it never changes the page layout: it is
// placed below the control (above it when there is no room below), aligned to the control's left edge, or to its right edge
// with data-popover-align="end" (the calendar then grows toward the left), and clamped inside the main content area so it can
// never overflow the page sideways. When two months do not fit the available width it shows one.
//
// Optional data-fixed-from="yyyy-MM-dd" on the root locks the check-in (the Walk-in check-in is the hotel date, set by the
// server): the calendar then starts at that day and cannot go before it, any single click picks the check-out, and the range
// is highlighted from the fixed day to it. Clear empties only the check-out.
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
        var fixedFrom = root.getAttribute("data-fixed-from") || "";
        var alignEnd = root.getAttribute("data-popover-align") === "end";
        var MARGIN = 8;
        if (fixedFrom) {
            fromField.value = fixedFrom;
        }

        // The calendar is built on first open, while the popover is visible: flatpickr measures its month width at
        // build time, which is zero inside a hidden container.
        var picker = null;
        var shownMonths = 1;
        // Width of the two-month popover once measured, so a roomier viewport can switch back from one month.
        var twoMonthWidth = 0;

        // The area the popover must stay inside: the main content area (never the sidebar), within the viewport.
        function boundsRect() {
            var area = root.closest("main") || document.documentElement;
            var rect = area.getBoundingClientRect();
            return { left: Math.max(rect.left, 0), right: Math.min(rect.right, document.documentElement.clientWidth) };
        }

        // Positions the (visible) popover as an overlay: horizontally by alignment, clamped into the content area; vertically
        // below the control, or above it when there is no room below and more above.
        function place() {
            popover.style.left = "";
            popover.style.right = "";
            popover.style.top = "";
            popover.style.bottom = "";
            // Narrow screens (the shared CSS media query): the popover takes the control's width with one month, so only the
            // vertical placement below applies.
            var narrow = window.matchMedia("(max-width: 40rem)").matches;
            var bounds = boundsRect();
            var available = bounds.right - bounds.left - 2 * MARGIN;
            var box = popover.getBoundingClientRect();
            if (picker && narrow && shownMonths === 2) {
                shownMonths = 1;
                picker.set("showMonths", 1);
                box = popover.getBoundingClientRect();
            } else if (picker && !narrow && shownMonths === 2) {
                twoMonthWidth = box.width;
                if (box.width > available) {
                    shownMonths = 1;
                    picker.set("showMonths", 1);
                    box = popover.getBoundingClientRect();
                }
            } else if (picker && !narrow && twoMonthWidth && twoMonthWidth <= available) {
                shownMonths = 2;
                picker.set("showMonths", 2);
                box = popover.getBoundingClientRect();
            }
            var anchor = root.getBoundingClientRect();
            if (!narrow) {
                var left = alignEnd ? anchor.right - box.width : anchor.left;
                left = Math.max(bounds.left + MARGIN, Math.min(left, bounds.right - MARGIN - box.width));
                popover.style.left = (left - anchor.left) + "px";
                popover.style.right = "auto";
            }
            var below = window.innerHeight - anchor.bottom - MARGIN;
            var above = anchor.top - MARGIN;
            if (box.height > below && above > below) {
                popover.style.top = "auto";
                popover.style.bottom = "calc(100% + var(--dropdown-gap))";
            }
        }

        function appliedRange() {
            if (fixedFrom) {
                // The fixed check-in is always selected, so the calendar shows it as the start of the range.
                return toField.value ? [fixedFrom, toField.value] : [fixedFrom];
            }
            return fromField.value && toField.value ? [fromField.value, toField.value] : [];
        }

        function ensurePicker() {
            if (picker) {
                return picker;
            }
            shownMonths = window.matchMedia("(min-width: 40rem)").matches ? 2 : 1;
            var calendarInput = document.createElement("input");
            calendarInput.type = "hidden";
            calendarHost.appendChild(calendarInput);
            picker = window.flatpickr(calendarInput, {
                inline: true,
                mode: "range",
                dateFormat: "Y-m-d",
                showMonths: shownMonths,
                locale: document.documentElement.lang === "vi" ? VIETNAMESE : { firstDayOfWeek: 1 },
                defaultDate: appliedRange(),
                minDate: fixedFrom || undefined,
                onChange: function (dates, text, instance) {
                    // Locked check-in: a click on any other day is the check-out, so the range always starts at the fixed day.
                    if (fixedFrom && dates.length === 1 && iso(dates[0]) !== fixedFrom) {
                        instance.setDate([fixedFrom, iso(dates[0])], false);
                        dates = instance.selectedDates;
                    }
                    refresh(dates);
                }
            });
            return picker;
        }

        // Mirrors the calendar selection into the Check-in / Check-out fields and enables Apply only for a complete,
        // ordered range.
        function refresh(dates) {
            inDisplay.value = fixedFrom ? display(parseIso(fixedFrom)) : dates.length > 0 ? display(dates[0]) : "";
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
            place();
            window.addEventListener("resize", place);
        }

        function close(returnFocus) {
            window.removeEventListener("resize", place);
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
            var hadRange = Boolean(fixedFrom ? toField.value : fromField.value || toField.value);
            if (picker) {
                picker.clear(false);
                if (fixedFrom) {
                    picker.setDate([fixedFrom], false);
                }
            }
            fromField.value = fixedFrom;
            toField.value = "";
            setSummary("", "");
            refresh(fixedFrom && picker ? picker.selectedDates : []);
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
                fromField.value = fixedFrom || from || "";
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
