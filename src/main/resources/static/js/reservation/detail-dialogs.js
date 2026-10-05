(() => {
    "use strict";

    // Reservation Detail dialogs (Cancel Reservation, Mark as No-show). Opening, closing and the usability checks
    // happen here; the backend validates every submission again and stays authoritative. A failed check follows the
    // Task33 convention: the shared error dialog summarises the problems, the fields show inline errors, and closing
    // the dialog moves focus to the first invalid field.
    const hasNativeDialog = typeof HTMLDialogElement !== "undefined";

    document.querySelectorAll("[data-detail-dialog]").forEach((trigger) => {
        trigger.addEventListener("click", () => {
            const dialog = document.getElementById(trigger.dataset.detailDialog);
            if (hasNativeDialog && dialog instanceof HTMLDialogElement && !dialog.open) {
                dialog.showModal();
            }
        });
    });

    document.querySelectorAll("[data-detail-dialog-close]").forEach((button) => {
        button.addEventListener("click", () => {
            const dialog = button.closest("dialog");
            if (dialog instanceof HTMLDialogElement) {
                dialog.close();
            }
        });
    });

    const errorElementOf = (field) => {
        const holder = field.closest(".form-field");
        return holder ? holder.querySelector("[data-detail-field-error]") : null;
    };

    const clearError = (field) => {
        field.removeAttribute("aria-invalid");
        const element = errorElementOf(field);
        if (element) {
            element.textContent = "";
            element.hidden = true;
        }
    };

    const showError = (field, message) => {
        field.setAttribute("aria-invalid", "true");
        const element = errorElementOf(field);
        if (element) {
            element.textContent = message;
            element.hidden = false;
        }
    };

    // The checks come from data attributes the template fills with localized text, so nothing is hard-coded here.
    const collectProblems = (form) => {
        const problems = [];
        form.querySelectorAll("[data-detail-field]").forEach((field) => {
            const required = field.dataset.detailRequiredMessage;
            if (required && field.value.trim() === "") {
                problems.push({ field, message: required });
            }
            const detailId = field.dataset.detailDetailField;
            if (detailId && field.value === field.dataset.detailDetailRequiredFor) {
                const detail = form.querySelector(`#${detailId}`);
                if (detail && detail.value.trim() === "") {
                    problems.push({ field: detail, message: field.dataset.detailDetailRequiredMessage });
                }
            }
        });
        return problems;
    };

    document.querySelectorAll("[data-detail-dialog-form]").forEach((form) => {
        form.querySelectorAll("[data-detail-field]").forEach((field) => {
            const reset = () => clearError(field);
            field.addEventListener("input", reset);
            field.addEventListener("change", () => {
                reset();
                const detail = field.dataset.detailDetailField && form.querySelector(`#${field.dataset.detailDetailField}`);
                if (detail) {
                    clearError(detail);
                }
            });
        });

        form.addEventListener("submit", (event) => {
            form.querySelectorAll("[data-detail-field]").forEach(clearError);
            const problems = collectProblems(form);
            if (problems.length === 0) {
                return;
            }
            event.preventDefault();
            problems.forEach((problem) => showError(problem.field, problem.message));
            const focusFirst = () => problems[0].field.focus();
            if (window.PmsFeedbackDialog) {
                window.PmsFeedbackDialog.show({
                    title: form.dataset.validationTitle,
                    message: form.dataset.validationIntro,
                    items: problems.map((problem) => problem.message),
                    onClose: focusFirst,
                });
            } else {
                focusFirst();
            }
        });
    });

    // A rejection the server reports (for example an active prepayment blocking the cancellation) opens the shared
    // error dialog on page load. When it is closed, focus returns to the workflow instead of the page top.
    const serverDialog = document.getElementById("feedback-dialog");
    if (serverDialog instanceof HTMLDialogElement && serverDialog.open) {
        serverDialog.addEventListener("close", () => {
            const workflow = document.getElementById("reservation-more")
                    || document.querySelector(".rd-actions a, .rd-actions button");
            if (workflow) {
                workflow.focus();
            }
        }, { once: true });
    }
})();
