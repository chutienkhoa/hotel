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

// Informational only: the backend independently and authoritatively rejects an OTA Payment with
// a blank reference regardless of this hint.
(() => {
    const methodSelect = document.getElementById("payment-method");
    const referenceInput = document.getElementById("payment-reference");
    const referenceHint = document.querySelector("[data-reference-hint]");
    if (!methodSelect || !referenceInput || !referenceHint) return;

    const refreshReferenceRequirement = () => {
        const isOta = methodSelect.value === "OTA";
        referenceInput.required = isOta;
        referenceHint.textContent = isOta ? referenceHint.dataset.requiredText : referenceHint.dataset.optionalText;
    };

    methodSelect.addEventListener("change", refreshReferenceRequirement);
    refreshReferenceRequirement();
})();

// Native <dialog> presentation only: dialogs open on their trigger, reopen after a validation error, and close on
// their Cancel/close controls. The server decides what each dialog contains and what the backend accepts.
(() => {
    const openDialog = (dialog) => {
        if (dialog && typeof dialog.showModal === "function" && !dialog.open) dialog.showModal();
    };

    document.querySelectorAll("[data-dialog-open]").forEach((trigger) => {
        trigger.addEventListener("click", () => openDialog(document.getElementById(trigger.dataset.dialogOpen)));
    });

    document.querySelectorAll("[data-dialog-close]").forEach((control) => {
        control.addEventListener("click", () => control.closest("dialog")?.close());
    });

    document.querySelectorAll("dialog[data-autoopen='true']").forEach(openDialog);
})();

// Charge row selection: a click (or Enter/Space on a focused row) shows that Charge's server-rendered detail block
// and highlights its row. Only one row is selected at a time. The data shown was rendered by the server.
(() => {
    const rows = document.querySelectorAll("[data-charge-row]");
    if (!rows.length) return;

    const selectCharge = (chargeId) => {
        rows.forEach((row) => {
            const selected = row.dataset.chargeRow === chargeId;
            row.classList.toggle("folio-charge-row--selected", selected);
            row.toggleAttribute("aria-current", selected);
        });
        document.querySelectorAll("[data-charge-detail]").forEach((panel) => {
            panel.hidden = panel.dataset.chargeDetail !== chargeId;
        });
    };

    rows.forEach((row) => {
        row.addEventListener("click", () => selectCharge(row.dataset.chargeRow));
        row.addEventListener("keydown", (event) => {
            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                selectCharge(row.dataset.chargeRow);
            }
        });
    });
})();

// Payment row selection: identical to Charge selection, keyed by Payment id so sorting never changes which Payment is selected.
(() => {
    const rows = document.querySelectorAll("[data-payment-row]");
    if (!rows.length) return;

    const selectPayment = (paymentId) => {
        rows.forEach((row) => {
            const selected = row.dataset.paymentRow === paymentId;
            row.classList.toggle("folio-charge-row--selected", selected);
            row.toggleAttribute("aria-current", selected);
        });
        document.querySelectorAll("[data-payment-detail]").forEach((panel) => {
            panel.hidden = panel.dataset.paymentDetail !== paymentId;
        });
    };

    rows.forEach((row) => {
        row.addEventListener("click", () => selectPayment(row.dataset.paymentRow));
        row.addEventListener("keydown", (event) => {
            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                selectPayment(row.dataset.paymentRow);
            }
        });
    });
})();

// Record Payment modal: closing it returns the form to its clean default and refreshes the currency-dependent fields.
(() => {
    const dialog = document.getElementById("folio-record-payment-dialog");
    const form = dialog?.querySelector("[data-payment-form]");
    if (!form) return;

    dialog.addEventListener("close", () => {
        form.reset();
        form.querySelector("#payment-currency")?.dispatchEvent(new Event("change"));
        form.querySelector("#payment-method")?.dispatchEvent(new Event("change"));
    });
})();

// Add Charge modal: Fixed Amount and Itemized are input modes shown as tabs, one form at a time. Switching tabs never
// submits. The footer's Add Charge button targets whichever form is visible. The Itemized total is a preview only;
// the backend calculation and validation remain authoritative.
(() => {
    const dialog = document.getElementById("folio-add-charge-dialog");
    if (!dialog) return;

    const tabs = dialog.querySelectorAll("[data-charge-mode-tab]");
    const panels = dialog.querySelectorAll("[data-charge-mode-panel]");
    const submit = document.getElementById("folio-charge-submit");
    const fields = dialog.querySelectorAll("input, select, textarea");

    const showMode = (mode) => {
        tabs.forEach((tab) => {
            const selected = tab.dataset.chargeModeTab === mode;
            tab.classList.toggle("is-active", selected);
            tab.setAttribute("aria-selected", String(selected));
            tab.tabIndex = selected ? 0 : -1;
        });
        panels.forEach((panel) => {
            panel.hidden = panel.dataset.chargeModePanel !== mode;
        });
        submit.setAttribute("form", `folio-charge-form-${mode === "fixed" ? "fixed" : "itemized"}`);
    };

    tabs.forEach((tab, index) => {
        tab.addEventListener("click", () => {
            showMode(tab.dataset.chargeModeTab);
        });
        tab.addEventListener("keydown", (event) => {
            if (event.key !== "ArrowRight" && event.key !== "ArrowLeft") return;
            event.preventDefault();
            const next = tabs[(index + (event.key === "ArrowRight" ? 1 : tabs.length - 1)) % tabs.length];
            next.focus();
            showMode(next.dataset.chargeModeTab);
        });
    });

    // Validation is shown only after the user has touched a field or attempted to submit. The browser's own bubble is
    // replaced by the inline message for the field, which is translated through data-required-message.
    const clientErrorFor = (field) => dialog.querySelector(`[data-client-error-for="${field.id}"]`);

    const showClientError = (field) => {
        field.toggleAttribute("data-interacted", true);
        const message = clientErrorFor(field);
        if (!message) return;
        message.textContent = field.dataset.requiredMessage || field.validationMessage;
        message.hidden = false;
    };

    const clearClientError = (field) => {
        const message = clientErrorFor(field);
        if (message && field.checkValidity()) {
            message.hidden = true;
            message.textContent = "";
        }
    };

    // Pressing a tab moves focus out of the active field, which fires blur. That blur must not validate the field,
    // because switching tabs never triggers validation. The flag is cleared once the press's focus change has run.
    let switchingMode = false;
    tabs.forEach((tab) => {
        tab.addEventListener("pointerdown", () => {
            switchingMode = true;
            setTimeout(() => { switchingMode = false; });
        });
    });

    fields.forEach((field) => {
        field.addEventListener("blur", () => {
            if (!switchingMode && !field.checkValidity()) showClientError(field);
        });
        field.addEventListener("invalid", (event) => {
            event.preventDefault();
            showClientError(field);
        });
        field.addEventListener("input", () => clearClientError(field));
        field.addEventListener("change", () => clearClientError(field));
    });

    // Live character counters for the optional Description fields.
    dialog.querySelectorAll("[data-counter-for]").forEach((counter) => {
        const textarea = document.getElementById(counter.dataset.counterFor);
        const update = () => {
            counter.textContent = `${textarea.value.length}/${textarea.maxLength}`;
        };
        textarea.addEventListener("input", update);
        update();
    });

    // Itemized total preview: Quantity x Unit Price in fixed-point arithmetic with six decimal places.
    const quantityInput = document.getElementById("itemized-charge-quantity");
    const unitPriceInput = document.getElementById("itemized-charge-unit-price");
    const totalOutput = dialog.querySelector("[data-charge-total]");
    const breakdownOutput = dialog.querySelector("[data-charge-breakdown]");
    const SCALE = 1000000n;

    const toScaled = (value) => {
        const normalized = value.replaceAll(",", "").replaceAll(" ", "").trim();
        if (!/^\d+(\.\d+)?$/.test(normalized)) return null;
        const [whole, fraction = ""] = normalized.split(".");
        return BigInt(whole + (fraction + "000000").slice(0, 6));
    };

    const formatTotal = (scaled) => {
        const whole = (scaled / SCALE).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ",");
        const fraction = (scaled % SCALE).toString().padStart(6, "0").replace(/0+$/, "");
        return `${fraction ? `${whole}.${fraction}` : whole} VND`;
    };

    const updateTotal = () => {
        const quantity = toScaled(quantityInput.value);
        const unitPrice = toScaled(unitPriceInput.value);
        const complete = quantity !== null && unitPrice !== null;
        totalOutput.textContent = complete ? formatTotal((quantity * unitPrice) / SCALE) : "—";
        breakdownOutput.textContent = complete
            ? `${quantityInput.value.trim()} × ${unitPriceInput.value.trim()} VND`
            : "";
    };

    quantityInput.addEventListener("input", updateTotal);
    unitPriceInput.addEventListener("input", updateTotal);

    // Closing the modal returns it to its clean default: Fixed Amount, empty fields, no validation shown.
    dialog.addEventListener("close", () => {
        dialog.querySelectorAll("form").forEach((form) => form.reset());
        fields.forEach((field) => {
            field.removeAttribute("data-interacted");
            const message = clientErrorFor(field);
            if (message) {
                message.hidden = true;
                message.textContent = "";
            }
        });
        dialog.querySelectorAll("[data-counter-for]").forEach((counter) => {
            const textarea = document.getElementById(counter.dataset.counterFor);
            counter.textContent = `0/${textarea.maxLength}`;
        });
        showMode("fixed");
        updateTotal();
    });

    updateTotal();
})();
