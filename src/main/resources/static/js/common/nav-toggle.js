(() => {
    // Task33 Batch 1A: keyboard-accessible mobile sidebar drawer. Below the 48rem breakpoint the sidebar
    // is hidden off-canvas by CSS (static/css/common/layout.css); this toggles it open/closed and keeps
    // aria-expanded, the backdrop, and focus in sync. On desktop/tablet the toggle button itself is
    // display: none (layout.css), so this script has nothing to attach to there and simply no-ops.
    const toggle = document.getElementById("header-nav-toggle");
    const sidebar = document.getElementById("sidebar-nav");
    const backdrop = document.getElementById("sidebar-backdrop");

    if (!toggle || !sidebar || !backdrop) {
        return;
    }

    // Programmatically focusable without joining the normal tab order, so opening the drawer can move
    // focus into it for keyboard/screen-reader users without changing tab order while it is closed.
    sidebar.setAttribute("tabindex", "-1");

    const isOpen = () => toggle.getAttribute("aria-expanded") === "true";

    const setOpen = (open) => {
        toggle.setAttribute("aria-expanded", open ? "true" : "false");
        toggle.setAttribute("aria-label", open ? toggle.dataset.labelClose : toggle.dataset.labelOpen);
        if (open) {
            sidebar.setAttribute("data-open", "true");
            backdrop.setAttribute("data-open", "true");
            sidebar.focus();
        } else {
            sidebar.removeAttribute("data-open");
            backdrop.removeAttribute("data-open");
        }
    };

    toggle.addEventListener("click", () => setOpen(!isOpen()));
    backdrop.addEventListener("click", () => {
        setOpen(false);
        toggle.focus();
    });
    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && isOpen()) {
            setOpen(false);
            toggle.focus();
        }
    });
})();
