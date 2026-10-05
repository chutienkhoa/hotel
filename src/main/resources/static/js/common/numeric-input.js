(function (root) {
    "use strict";

    // The one numeric text-input behaviour of the PMS. An input opts in with data-numeric:
    //   "integer"  digits only, shown as typed (counts: Adults, Children, Quantity)
    //   "vnd"      digits only, shown with thousands separators (whole-dong amounts)
    //   "decimal"  digits and one decimal point, integer part shown with thousands separators; the number of fraction
    //              digits allowed is data-numeric-scale, or follows a currency <select> named by
    //              data-numeric-scale-from (each <option> carrying data-fraction-digits). Used for foreign-currency
    //              amounts and exchange rates.
    // Letters, signs, a second decimal point and a typed comma are refused as you type; a paste is accepted only when it
    // is a clean number for that field (never partly kept); the thousands commas are added and removed by this script and
    // are left out of what is submitted. A negative value is never offered: no numeric field of the PMS takes one.
    // This is typing assistance only; the server validates every value again.

    var COMMA = ",";
    var DEFAULT_DECIMAL_SCALE = 2;
    // Largest whole parts the stored values can hold: money and rates are NUMERIC(19,6), counts are 32-bit integers.
    var MAX_MONEY_WHOLE_DIGITS = 13;
    var MAX_COUNT_DIGITS = 9;

    /** Resolves how one input behaves right now: whether it groups thousands and how many fraction digits it takes. */
    function modeOf(input) {
        var type = input.dataset.numeric;
        if (type === "integer") {
            return { grouped: false, scale: 0, maxWhole: MAX_COUNT_DIGITS };
        }
        if (type === "vnd") {
            return { grouped: true, scale: 0, maxWhole: MAX_MONEY_WHOLE_DIGITS };
        }
        return { grouped: true, scale: scaleOf(input), maxWhole: MAX_MONEY_WHOLE_DIGITS };
    }

    function scaleOf(input) {
        var sourceSelector = input.dataset.numericScaleFrom;
        if (sourceSelector && typeof document !== "undefined") {
            var select = document.querySelector(sourceSelector);
            var option = select && select.options && select.options[select.selectedIndex];
            var digits = option ? Number(option.dataset.fractionDigits) : NaN;
            if (Number.isInteger(digits) && digits >= 0) {
                return digits;
            }
        }
        var fixed = Number(input.dataset.numericScale);
        return Number.isInteger(fixed) && fixed >= 0 ? fixed : DEFAULT_DECIMAL_SCALE;
    }

    function group(digits) {
        return digits.replace(/\B(?=(\d{3})+(?!\d))/g, COMMA);
    }

    function withoutCommas(text) {
        return String(text).replace(/,/g, "");
    }

    /** True for text that is a clean number for the mode: whole digits, or grouped / decimal as the mode allows. */
    function isCleanNumber(text, mode) {
        var trimmed = String(text).trim();
        if (!mode.grouped) {
            return /^\d+$/.test(trimmed) && trimmed.length <= mode.maxWhole;
        }
        var match = /^(\d+|\d{1,3}(,\d{3})+)(\.(\d+))?$/.exec(trimmed);
        if (!match) {
            return false;
        }
        var fraction = match[4];
        var whole = match[1].replace(/,/g, "");
        return whole.length <= mode.maxWhole && (fraction === undefined || (mode.scale > 0 && fraction.length <= mode.scale));
    }

    /** The number of a clean text without grouping commas. */
    function cleanDigits(text) {
        return withoutCommas(String(text).trim());
    }

    /**
     * Whether text typed or inserted at the selection keeps the field a valid number prefix: only digits (and one
     * decimal point where the mode has fraction digits, within its limit). Grouping commas already in the field are
     * ignored, but a comma in the inserted text is refused.
     */
    function accepts(value, start, end, data, mode) {
        if (!/^[0-9.]*$/.test(data)) {
            return false;
        }
        var candidate = withoutCommas(value.slice(0, start) + data + value.slice(end));
        if (mode.scale === 0) {
            return /^\d*$/.test(candidate) && candidate.length <= mode.maxWhole;
        }
        var match = /^(\d*)(\.(\d*))?$/.exec(candidate);
        return Boolean(match) && match[1].length <= mode.maxWhole
            && (match[3] === undefined || match[3].length <= mode.scale);
    }

    /**
     * Reduces a value to what the mode allows (digits, one decimal point, at most scale fraction digits), groups the
     * integer part when the mode groups, and keeps the caret after the same number of significant characters.
     *
     * @returns {{value: string, caret: number}}
     */
    function render(value, caret, mode) {
        var significant = function (text) {
            return text.replace(mode.scale > 0 ? /[^0-9.]/g : /[^0-9]/g, "");
        };
        var before = significant(String(value).slice(0, caret)).length;
        var text = significant(String(value));
        var dot = text.indexOf(".");
        var whole = (dot < 0 ? text : text.slice(0, dot)).slice(0, mode.maxWhole);
        var fraction = dot < 0 ? null : text.slice(dot + 1).replace(/\./g, "").slice(0, mode.scale);
        if (fraction !== null && whole === "") {
            whole = "0";
            before += 1;
        }
        var result = (mode.grouped ? group(whole) : whole) + (fraction === null ? "" : "." + fraction);
        var position = 0;
        var seen = 0;
        while (position < result.length && seen < before) {
            if (result.charAt(position) !== COMMA) {
                seen += 1;
            }
            position += 1;
        }
        return { value: result, caret: position };
    }

    function apply(input, value, caret) {
        var rendered = render(value, caret, modeOf(input));
        input.value = rendered.value;
        if (typeof input.setSelectionRange === "function") {
            input.setSelectionRange(rendered.caret, rendered.caret);
        }
    }

    function selection(input) {
        var start = input.selectionStart === null || input.selectionStart === undefined ? input.value.length : input.selectionStart;
        var end = input.selectionEnd === null || input.selectionEnd === undefined ? start : input.selectionEnd;
        return { start: start, end: end };
    }

    function initialize(input) {
        if (!input || input.dataset.numericReady) {
            return;
        }
        input.dataset.numericReady = "true";

        // A server-rendered value that is not a clean number for this field (for example a rejected "1500000.5" in a
        // whole-dong field) is shown as it was submitted, so it is never silently changed into a different amount.
        var mode = modeOf(input);
        var shown = input.value.trim();
        if (shown) {
            if (mode.grouped && /^\d+\.\d+$/.test(shown)) {
                shown = shown.replace(/(\.\d*?)0+$/, "$1").replace(/\.$/, "");
            }
            if (isCleanNumber(shown, mode)) {
                input.value = mode.grouped ? render(cleanDigits(shown), shown.length, mode).value : cleanDigits(shown);
            }
        }

        input.addEventListener("beforeinput", function (event) {
            var type = event.inputType || "";
            var current = modeOf(input);
            if (type === "insertFromPaste" || type === "insertFromDrop" || type === "insertFromYank") {
                var text = event.data;
                if ((text === null || text === undefined) && event.dataTransfer) {
                    text = event.dataTransfer.getData("text");
                }
                event.preventDefault();
                if (typeof text === "string" && isCleanNumber(text, current)) {
                    var range = selection(input);
                    var digits = cleanDigits(text);
                    if (accepts(input.value, range.start, range.end, digits, current)) {
                        apply(input, input.value.slice(0, range.start) + digits + input.value.slice(range.end),
                            range.start + digits.length);
                    }
                }
                return;
            }
            if (type.indexOf("insert") === 0 && typeof event.data === "string") {
                var area = selection(input);
                if (!accepts(input.value, area.start, area.end, event.data, current)) {
                    event.preventDefault();
                }
            }
        });

        // Whatever still gets in (mobile keyboards, autofill, composition) is reduced to what the field allows.
        input.addEventListener("input", function () {
            apply(input, input.value, selection(input).start);
        });

        // A currency <select> that sets the fraction digits: re-render so the field follows its new limit.
        var sourceSelector = input.dataset.numericScaleFrom;
        var source = sourceSelector && typeof document !== "undefined" ? document.querySelector(sourceSelector) : null;
        if (source) {
            source.addEventListener("change", function () {
                if (input.value && isCleanNumber(input.value, modeOf(input)) === false) {
                    return;
                }
                apply(input, input.value, input.value.length);
            });
        }
    }

    // The grouped value is submitted as plain digits, without touching what is shown (so a form that stays on the page
    // after a client-side check keeps its separators).
    function submitWithoutGrouping(event) {
        event.target.querySelectorAll('[data-numeric="vnd"], [data-numeric="decimal"]').forEach(function (input) {
            if (input.name) {
                event.formData.set(input.name, withoutCommas(input.value));
            }
        });
    }

    function initializeAll() {
        document.querySelectorAll("[data-numeric]").forEach(initialize);
        document.querySelectorAll("form").forEach(function (form) {
            form.addEventListener("formdata", submitWithoutGrouping);
        });
    }

    /** Whether text is a clean number for this particular input (its mode: whole count, money, decimal). */
    function isCleanNumberFor(input, text) {
        return isCleanNumber(text, modeOf(input));
    }

    root.PmsNumericInput = {
        accepts: accepts,
        cleanDigits: cleanDigits,
        initialize: initialize,
        isCleanNumber: isCleanNumber,
        isCleanNumberFor: isCleanNumberFor,
        render: render
    };

    if (typeof document !== "undefined") {
        if (document.readyState === "loading") {
            document.addEventListener("DOMContentLoaded", initializeAll);
        } else {
            initializeAll();
        }
    }
})(typeof window !== "undefined" ? window : globalThis);
