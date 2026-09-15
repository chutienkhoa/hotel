(() => {
    const moneyInputSelector = ".js-money-input";

    const normalize = (value) => value.replaceAll(",", "").replaceAll(" ", "");

    const format = (value) => {
        const normalized = normalize(value);
        if (!normalized) {
            return "";
        }

        const [rawWholePart, rawFractionPart] = normalized.split(".", 2);
        const wholePart = rawWholePart.replace(/\D/g, "");
        const fractionPart = (rawFractionPart?.replace(/\D/g, "") ?? "").replace(/0+$/, "");
        const formattedWholePart = (wholePart || "0").replace(/\B(?=(\d{3})+(?!\d))/g, ",");
        return fractionPart ? `${formattedWholePart}.${fractionPart}` : formattedWholePart;
    };

    const initializeMoneyInput = (input) => {
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

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initializeMoneyInputs);
    } else {
        initializeMoneyInputs();
    }
})();
