(() => {
    "use strict";

    // Task33 Batch 1B: opt-in duplicate-submit guard (spec sec. 10 "Loading/submission"). Only forms
    // marked data-submit-guard are affected, so unrelated forms keep their default behavior; this is
    // infrastructure plus a representative integration, not a site-wide change (see
    // docs/specs/Task33_UI_UX_Specification.md sec. 8.3).
    //
    // Ordering with layout/confirmation.html's confirmation.js matters: this script loads after it (see
    // layout/base.html), so on a form that has both data-confirm-* and data-submit-guard, confirmation.js's
    // listener runs first. Its first (unconfirmed) submit calls preventDefault() and reopens as a dialog;
    // only the second, confirmed resubmission reaches here with event.defaultPrevented still false.
    document.querySelectorAll("form[data-submit-guard]").forEach((form) => {
        form.addEventListener("submit", (event) => {
            if (event.defaultPrevented) {
                return;
            }
            const submitter = event.submitter;
            if (!(submitter instanceof HTMLButtonElement) && !(submitter instanceof HTMLInputElement)) {
                return;
            }
            submitter.disabled = true;
            submitter.setAttribute("aria-busy", "true");
            const loadingText = submitter.dataset.loadingText;
            if (loadingText) {
                submitter.textContent = loadingText;
            }
        });
    });
})();
