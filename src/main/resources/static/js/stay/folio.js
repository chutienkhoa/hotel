(() => {
    const form = document.querySelector("[data-payment-form]");
    if (!form) return;

    const currencySelect = document.getElementById("payment-currency");
    const amountInput = document.getElementById("payment-amount");
    const exchangeRateField = form.querySelector("[data-exchange-rate-field]");
    const exchangeRateInput = document.getElementById("payment-exchange-rate");
    const appliedPreview = document.getElementById("payment-applied-preview");
    if (!currencySelect || !amountInput || !exchangeRateField || !exchangeRateInput || !appliedPreview) return;

    const folioCurrency = form.dataset.reservationCurrency;

    // Fixed-point decimal helpers (scale 6, matching the backend BigDecimal precision) so the
    // informational preview never relies on binary floating-point arithmetic.
    const SCALE = 6;
    const SCALE_FACTOR = 10n ** BigInt(SCALE);

    const toScaledBigInt = (rawValue) => {
        const normalized = String(rawValue ?? "").replaceAll(",", "").replaceAll(" ", "").trim();
        if (!normalized || Number.isNaN(Number(normalized))) return null;
        const negative = normalized.startsWith("-");
        const unsigned = negative ? normalized.slice(1) : normalized;
        const [rawWhole, rawFraction] = unsigned.split(".", 2);
        const whole = (rawWhole || "0").replace(/\D/g, "") || "0";
        const fraction = ((rawFraction ?? "").replace(/\D/g, "") + "0".repeat(SCALE)).slice(0, SCALE);
        const scaled = BigInt(`${whole}${fraction}`);
        return negative ? -scaled : scaled;
    };

    const fromScaledBigInt = (scaled) => {
        const negative = scaled < 0n;
        const absScaled = negative ? -scaled : scaled;
        const whole = absScaled / SCALE_FACTOR;
        const fraction = (absScaled % SCALE_FACTOR).toString().padStart(SCALE, "0").replace(/0+$/, "");
        const groupedWhole = whole.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ",");
        const sign = negative ? "-" : "";
        return fraction ? `${sign}${groupedWhole}.${fraction}` : `${sign}${groupedWhole}`;
    };

    const divideRoundHalfUp = (numerator, denominator) => {
        if (denominator === 0n) return null;
        const negative = (numerator < 0n) !== (denominator < 0n);
        const absNumerator = numerator < 0n ? -numerator : numerator;
        const absDenominator = denominator < 0n ? -denominator : denominator;
        const quotient = absNumerator / absDenominator;
        const remainder = absNumerator % absDenominator;
        const roundedUp = remainder * 2n >= absDenominator ? quotient + 1n : quotient;
        return negative ? -roundedUp : roundedUp;
    };

    const calculateAppliedScaled = (amountScaled, rateScaled, paymentCurrency) => {
        if (paymentCurrency === folioCurrency) return amountScaled;
        if (rateScaled === null || rateScaled <= 0n) return null;
        return paymentCurrency === "USD"
            ? divideRoundHalfUp(amountScaled * rateScaled, SCALE_FACTOR)
            : divideRoundHalfUp(amountScaled * SCALE_FACTOR, rateScaled);
    };

    /**
     * Enables or disables the Exchange Rate input based on the selected Payment currency. The
     * field stays visible at all times — only its disabled state (and native disabled styling)
     * changes — so staff always see where the rate belongs instead of the form shifting layout.
     */
    const updateExchangeRateVisibility = () => {
        const paymentCurrency = currencySelect.value;
        const sameCurrency = !paymentCurrency || paymentCurrency === folioCurrency;
        exchangeRateInput.disabled = sameCurrency;
        if (sameCurrency) {
            exchangeRateInput.value = "";
        }
    };

    /** Recalculates the informational Applied to Folio preview from the current form values. */
    const updateAppliedPreview = () => {
        const paymentCurrency = currencySelect.value;
        if (!paymentCurrency) {
            appliedPreview.textContent = "—";
            return;
        }
        const amountScaled = toScaledBigInt(amountInput.value);
        if (amountScaled === null) {
            appliedPreview.textContent = "—";
            return;
        }
        const rateScaled = paymentCurrency === folioCurrency ? null : toScaledBigInt(exchangeRateInput.value);
        const appliedScaled = calculateAppliedScaled(amountScaled, rateScaled, paymentCurrency);
        appliedPreview.textContent = appliedScaled === null
            ? "—"
            : `${fromScaledBigInt(appliedScaled)} ${folioCurrency}`;
    };

    const refresh = () => {
        updateExchangeRateVisibility();
        updateAppliedPreview();
    };

    currencySelect.addEventListener("change", refresh);
    amountInput.addEventListener("input", updateAppliedPreview);
    exchangeRateInput.addEventListener("input", updateAppliedPreview);

    refresh();
})();
