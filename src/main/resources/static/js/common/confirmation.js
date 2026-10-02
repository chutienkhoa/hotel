(() => {
    const dialog = document.getElementById("confirmation-dialog");

    if (typeof HTMLDialogElement === "undefined" || !(dialog instanceof HTMLDialogElement)) {
        return;
    }

    const title = dialog.querySelector("#confirmation-dialog-title");
    const message = dialog.querySelector("#confirmation-dialog-message");
    const cancelButton = dialog.querySelector("[data-confirm-cancel]");
    const confirmButton = dialog.querySelector("[data-confirm-submit]");
    const confirmedForms = new WeakSet();
    let pendingForm = null;
    let pendingSubmitter = null;

    if (!title || !message || !cancelButton || !confirmButton) {
        return;
    }

    const clearPendingSubmission = (returnFocus) => {
        const submitter = pendingSubmitter;
        pendingForm = null;
        pendingSubmitter = null;
        confirmButton.disabled = false;

        if (returnFocus && submitter) {
            submitter.focus();
        }
    };

    const openConfirmation = (event) => {
        const form = event.currentTarget;

        // A submit control with its own formaction (for example a "+ Create New Guest" escape
        // hatch, or a wizard "Back" control) performs a different, non-destructive action than
        // the form's own confirmed action, so it must bypass that unrelated confirmation dialog.
        if (event.submitter instanceof HTMLElement && event.submitter.hasAttribute("formaction")) {
            return;
        }

        if (confirmedForms.has(form)) {
            confirmedForms.delete(form);
            return;
        }

        event.preventDefault();
        pendingForm = form;
        pendingSubmitter = event.submitter instanceof HTMLButtonElement
                || event.submitter instanceof HTMLInputElement
                ? event.submitter
                : null;
        title.textContent = form.dataset.confirmTitle;
        message.textContent = form.dataset.confirmMessage;
        confirmButton.textContent = form.dataset.confirmLabel;
        dialog.dataset.confirmSeverity = form.dataset.confirmSeverity;
        dialog.showModal();
    };

    document.querySelectorAll("form[data-confirm-title][data-confirm-message][data-confirm-label][data-confirm-severity]")
            .forEach((form) => form.addEventListener("submit", openConfirmation));

    cancelButton.addEventListener("click", () => dialog.close());

    dialog.addEventListener("cancel", (event) => {
        event.preventDefault();
        dialog.close();
    });

    dialog.addEventListener("close", () => {
        if (pendingForm) {
            clearPendingSubmission(true);
        }
    });

    confirmButton.addEventListener("click", () => {
        const form = pendingForm;
        const submitter = pendingSubmitter;

        if (!form) {
            return;
        }

        confirmButton.disabled = true;
        pendingForm = null;
        pendingSubmitter = null;
        dialog.close();
        confirmedForms.add(form);
        if (submitter) {
            form.requestSubmit(submitter);
        } else {
            form.requestSubmit();
        }
    });
})();
