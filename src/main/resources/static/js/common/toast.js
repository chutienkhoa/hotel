(() => {
    "use strict";

    const region = document.getElementById("toast-region");
    if (!region) {
        return;
    }

    const dismiss = (toast) => {
        toast.remove();
    };

    const wireUp = (toast) => {
        const closeButton = toast.querySelector("[data-toast-close]");
        if (closeButton) {
            closeButton.addEventListener("click", () => dismiss(toast));
        }
        const autoDismissAfter = Number(toast.dataset.toastAutoDismiss);
        if (autoDismissAfter > 0) {
            setTimeout(() => dismiss(toast), autoDismissAfter);
        }
    };

    region.querySelectorAll("[data-toast]").forEach(wireUp);

    // Exposed for a future AJAX/in-page operation to raise a toast without a full page navigation. No
    // current controller needs this (every success path today is a server-rendered redirect, already
    // covered by the fragment above), so this is infrastructure only until a later batch calls it.
    window.PmsToast = {
        show(message, variant) {
            const toast = document.createElement("div");
            toast.className = "toast toast--" + (variant === "warning" ? "warning" : "success");
            toast.setAttribute("data-toast", "");

            const text = document.createElement("p");
            text.className = "toast-message";
            text.textContent = message;

            const closeButton = document.createElement("button");
            closeButton.type = "button";
            closeButton.className = "toast-close";
            closeButton.setAttribute("data-toast-close", "");
            closeButton.setAttribute(
                "aria-label",
                window.PmsI18n ? window.PmsI18n.t("js.toast.close", "Close") : "Close");
            closeButton.textContent = "×";

            toast.append(text, closeButton);
            region.appendChild(toast);
            wireUp(toast);

            if (variant !== "warning") {
                toast.dataset.toastAutoDismiss = "6000";
                setTimeout(() => dismiss(toast), 6000);
            }
        },
    };
})();
