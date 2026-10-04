(() => {
    "use strict";

    const form = document.getElementById("room-change-form");
    const reason = document.getElementById("reason");
    if (!form || !reason) return;

    const reasonError = document.getElementById("reason-error");

    // Scrolls the control into view if needed and focuses it, without resetting any form state.
    const focusControl = (control) => {
        control.scrollIntoView({ block: "center" });
        control.focus({ preventScroll: true });
    };

    const focusFirstInvalid = () => {
        const invalid = form.querySelector('[aria-invalid="true"]');
        if (invalid) focusControl(invalid);
    };

    // Presentation only: the server validates the same rule and stays authoritative. This shows the missing Reason
    // before the round trip, with the same inline message and the same shared dialog.
    form.addEventListener("submit", (event) => {
        if (reason.value) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        const message = form.dataset.reasonRequired;
        reason.setAttribute("aria-invalid", "true");
        if (reasonError) reasonError.textContent = message;
        const dialog = window.PmsFeedbackDialog;
        if (!dialog) {
            focusFirstInvalid();
            return;
        }
        dialog.show({
            title: form.dataset.errorTitle,
            message: form.dataset.errorIntro,
            items: [message],
            onClose: () => window.setTimeout(focusFirstInvalid, 0),
        });
    }, true);

    // The server rejected the submit: its dialog is already open; focus the first invalid field once it is closed.
    const serverDialog = document.getElementById("feedback-dialog");
    const serverList = serverDialog && serverDialog.querySelector("#feedback-dialog-list");
    if (serverDialog && serverList && !serverList.hidden && serverList.children.length > 0) {
        serverDialog.addEventListener("close", () => window.setTimeout(focusFirstInvalid, 0), { once: true });
    }

    // A field stops being marked once it is edited.
    const clearOnEdit = (event) => {
        const target = event.target;
        if (!(target instanceof Element) || target.getAttribute("aria-invalid") !== "true") return;
        target.setAttribute("aria-invalid", "false");
        const holder = target.closest(".form-field, .room-change-card");
        const message = holder && holder.querySelector(".field-error");
        if (message) message.textContent = "";
    };
    form.addEventListener("change", clearOnEdit);
})();
