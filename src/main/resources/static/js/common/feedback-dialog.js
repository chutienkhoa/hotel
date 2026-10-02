(() => {
    "use strict";

    const dialog = document.getElementById("feedback-dialog");

    if (typeof HTMLDialogElement === "undefined" || !(dialog instanceof HTMLDialogElement)) {
        return;
    }

    const message = dialog.querySelector("#feedback-dialog-message");
    const closeButton = dialog.querySelector("[data-feedback-dialog-close]");

    // Nothing to show: the page rendered the dialog markup (always present once the fragment is
    // included) but neither errorMessage nor systemErrorMessage was set this request.
    if (!message || !closeButton || !message.textContent.trim()) {
        return;
    }

    closeButton.addEventListener("click", () => dialog.close());

    dialog.addEventListener("cancel", (event) => {
        event.preventDefault();
        dialog.close();
    });

    dialog.showModal();
})();
