(() => {
    "use strict";

    const dialog = document.getElementById("feedback-dialog");

    if (typeof HTMLDialogElement === "undefined" || !(dialog instanceof HTMLDialogElement)) {
        return;
    }

    const title = dialog.querySelector("#feedback-dialog-title");
    const message = dialog.querySelector("#feedback-dialog-message");
    const closeButton = dialog.querySelector("[data-feedback-dialog-close]");

    if (!message || !closeButton) {
        return;
    }

    closeButton.addEventListener("click", () => dialog.close());

    dialog.addEventListener("cancel", (event) => {
        event.preventDefault();
        dialog.close();
    });

    // Lets a page raise this same dialog for a client-detected, recoverable problem (for example the Walk-in
    // form's missing nightly rate) without a second dialog component. Presentation only: the server still
    // validates. onClose runs once, after the dialog has closed (the page uses it to restore focus).
    window.PmsFeedbackDialog = {
        show({ title: heading, message: text, onClose }) {
            if (title && heading) {
                title.textContent = heading;
            }
            message.textContent = text;
            if (typeof onClose === "function") {
                dialog.addEventListener("close", onClose, { once: true });
            }
            if (!dialog.open) {
                dialog.showModal();
            }
        },
    };

    // Nothing to show: the page rendered the dialog markup (always present once the fragment is
    // included) but neither errorMessage nor systemErrorMessage was set this request.
    if (!message.textContent.trim()) {
        return;
    }

    dialog.showModal();
})();
