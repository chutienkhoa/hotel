(() => {
    const moneyInputSelector = ".js-money-input";

    // Only digits with at most one dot as the decimal separator are grouped. Anything else (a second
    // dot, a stray letter) is left exactly as typed rather than reinterpreted: silently dropping the
    // unsupported part would change the amount staff entered.
    const supportedNumber = /^\d*(\.\d*)?$/;

    const normalize = (value) => value.replaceAll(",", "").replaceAll(" ", "");

    const groupWholePart = (digits) => digits.replace(/\B(?=(\d{3})+(?!\d))/g, ",");

    const format = (value) => {
        const normalized = normalize(value);
        if (!normalized) {
            return "";
        }
        if (!supportedNumber.test(normalized)) {
            return value;
        }

        const separatorIndex = normalized.indexOf(".");
        if (separatorIndex < 0) {
            return groupWholePart(normalized);
        }
        // The separator and every fractional digit are preserved as typed, including a separator that
        // is not followed by a digit yet and trailing zeros such as "20.50".
        return groupWholePart(normalized.slice(0, separatorIndex)) + "." + normalized.slice(separatorIndex + 1);
    };

    const initializeMoneyInput = (input) => {
        if (input.dataset.moneyInitialized) {
            return;
        }
        input.dataset.moneyInitialized = "true";
        input.value = format(input.value);
        input.addEventListener("input", () => {
            input.value = format(input.value);
        });
    };

    const normalizeBeforeSubmit = (form) => {
        form.querySelectorAll(moneyInputSelector).forEach((input) => {
            input.value = normalize(input.value);
        });
    };

    const initializeMoneyInputs = () => {
        document.querySelectorAll(moneyInputSelector).forEach(initializeMoneyInput);
        document.querySelectorAll("form").forEach((form) => {
            form.addEventListener("submit", () => normalizeBeforeSubmit(form));
        });
    };

    // Lets a page that adds money inputs after load (for example a table built from fetched rows) give them the same behaviour.
    window.PmsMoneyInput = { initialize: initializeMoneyInput };

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initializeMoneyInputs);
    } else {
        initializeMoneyInputs();
    }
})();
