(function (root) {
    "use strict";

    // The one Number Stepper of the PMS: [ - ] [ editable whole number ] [ + ] for quantity / count fields.
    // An input opts in with data-stepper; this script wraps it and adds the two buttons, so the input keeps its id,
    // name, th:field binding, validation messages and every listener that other scripts attached to it.
    //   data-stepper           marks the input (required)
    //   data-stepper-min       lowest value the minus button reaches (omit when the field has no lower bound)
    //   data-stepper-max       highest value the plus button reaches (omit unless a business rule defines one)
    //   data-stepper-step      amount per click, default 1
    // disabled, readonly and required are read from the input itself. The value stays directly editable by keyboard
    // (typing is filtered by numeric-input.js via data-numeric="integer"; ArrowUp / ArrowDown also step it). This is
    // typing assistance only: the server validates every value again, and an out-of-range typed value is left as typed.

    var i18n = function (key, fallback) {
        return root.PmsI18n ? root.PmsI18n.t(key, fallback) : fallback;
    };

    var SVG_OPEN = '<svg aria-hidden="true" fill="none" height="16" stroke="currentColor" stroke-linecap="round" '
        + 'stroke-linejoin="round" stroke-width="1.8" viewBox="0 0 24 24" width="16">';
    var MINUS_ICON = SVG_OPEN + '<path d="M5 12h14"></path></svg>';
    var PLUS_ICON = SVG_OPEN + '<path d="M12 5v14M5 12h14"></path></svg>';

    function optionalInteger(text) {
        if (text === undefined || text === null || text === "") {
            return null;
        }
        var number = Number(text);
        return Number.isInteger(number) ? number : null;
    }

    function configOf(input) {
        var step = optionalInteger(input.dataset.stepperStep);
        return {
            min: optionalInteger(input.dataset.stepperMin),
            max: optionalInteger(input.dataset.stepperMax),
            step: step !== null && step > 0 ? step : 1
        };
    }

    /** The whole number currently in the field, or null when it is empty or not a clean whole number. */
    function valueOf(input) {
        var text = String(input.value).trim();
        return /^\d+$/.test(text) ? Number(text) : null;
    }

    /** The value one click in the given direction (+1 / -1) leads to, or null when that click does nothing. */
    function nextValue(input, direction) {
        var config = configOf(input);
        var current = valueOf(input);
        var next;
        if (current === null) {
            if (direction < 0) {
                return null;
            }
            next = config.min !== null ? config.min : config.step;
        } else {
            next = current + direction * config.step;
        }
        if (config.min !== null && next < config.min) {
            next = config.min;
        }
        if (config.max !== null && next > config.max) {
            next = config.max;
        }
        if (next < 0 || next === current) {
            return null;
        }
        if (root.PmsNumericInput && !root.PmsNumericInput.isCleanNumberFor(input, String(next))) {
            return null;
        }
        return next;
    }

    function labelOf(input) {
        var label = input.id ? document.querySelector('label[for="' + input.id + '"]') : null;
        return label ? label.textContent.replace(/\*/g, "").replace(/\s+/g, " ").trim() : "";
    }

    function button(kind, icon, text) {
        var element = document.createElement("button");
        element.type = "button";
        element.className = "number-stepper__button number-stepper__button--" + kind;
        element.setAttribute("aria-label", text);
        element.innerHTML = icon;
        return element;
    }

    function sync(stepper) {
        var input = stepper.input;
        var locked = input.disabled || input.readOnly;
        stepper.minus.disabled = locked || nextValue(input, -1) === null;
        stepper.plus.disabled = locked || nextValue(input, 1) === null;
    }

    function step(stepper, direction) {
        var input = stepper.input;
        if (input.disabled || input.readOnly) {
            return;
        }
        var next = nextValue(input, direction);
        if (next !== null) {
            input.value = String(next);
            input.dispatchEvent(new Event("input", { bubbles: true }));
            input.dispatchEvent(new Event("change", { bubbles: true }));
        }
        sync(stepper);
    }

    function initialize(input) {
        if (!input || input.dataset.stepperReady) {
            return;
        }
        input.dataset.stepperReady = "true";

        var label = labelOf(input);
        var wrapper = document.createElement("div");
        wrapper.className = "number-stepper";
        input.parentNode.insertBefore(wrapper, input);
        input.classList.add("number-stepper__input");
        var stepper = {
            input: input,
            minus: button("decrease", MINUS_ICON, (i18n("js.stepper.decrease", "Decrease") + " " + label).trim()),
            plus: button("increase", PLUS_ICON, (i18n("js.stepper.increase", "Increase") + " " + label).trim())
        };
        wrapper.append(stepper.minus, input, stepper.plus);

        stepper.minus.addEventListener("click", function () { step(stepper, -1); });
        stepper.plus.addEventListener("click", function () { step(stepper, 1); });
        input.addEventListener("keydown", function (event) {
            if (event.key === "ArrowUp" || event.key === "ArrowDown") {
                event.preventDefault();
                step(stepper, event.key === "ArrowUp" ? 1 : -1);
            }
        });
        input.addEventListener("input", function () { sync(stepper); });
        input.addEventListener("change", function () { sync(stepper); });
        // The value can also be set by script (a restored draft) or the input disabled / made read-only later; those do
        // not fire input events, so the buttons are brought up to date whenever the control is about to be used.
        wrapper.addEventListener("pointerenter", function () { sync(stepper); });
        wrapper.addEventListener("focusin", function () { sync(stepper); });
        if (typeof MutationObserver !== "undefined") {
            new MutationObserver(function () { sync(stepper); })
                .observe(input, { attributes: true, attributeFilter: ["disabled", "readonly", "data-stepper-min", "data-stepper-max", "data-stepper-step"] });
        }
        sync(stepper);
    }

    function initializeAll() {
        document.querySelectorAll("input[data-stepper]").forEach(initialize);
    }

    root.PmsNumberStepper = { initialize: initialize, nextValue: nextValue };

    if (typeof document !== "undefined") {
        if (document.readyState === "loading") {
            document.addEventListener("DOMContentLoaded", initializeAll);
        } else {
            initializeAll();
        }
    }
})(typeof window !== "undefined" ? window : globalThis);
