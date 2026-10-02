(() => {
    // Task33 Batch 1A: accessible header account menu (username, language switch, logout). Visibility is
    // driven by the `hidden` attribute rather than a CSS display rule, so a JavaScript failure leaves the
    // menu simply closed rather than stuck open. Both menu actions (language link, logout submit) cause a
    // full page navigation, which already satisfies "closes after selection" without extra code here.
    const trigger = document.getElementById("header-account-trigger");
    const menu = document.getElementById("header-account-menu");

    if (!trigger || !menu) {
        return;
    }

    const isOpen = () => trigger.getAttribute("aria-expanded") === "true";

    const setOpen = (open) => {
        trigger.setAttribute("aria-expanded", open ? "true" : "false");
        menu.hidden = !open;
    };

    trigger.addEventListener("click", () => setOpen(!isOpen()));

    document.addEventListener("click", (event) => {
        if (isOpen() && !trigger.contains(event.target) && !menu.contains(event.target)) {
            setOpen(false);
        }
    });

    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && isOpen()) {
            setOpen(false);
            trigger.focus();
        }
    });
})();
