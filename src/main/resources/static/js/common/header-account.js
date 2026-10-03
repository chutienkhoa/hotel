(() => {
    // Task33 Batch 1A (account menu) + global header language-switcher redesign (language popover):
    // accessible header dropdowns (account/username/logout, and the flag-based language switcher).
    // Visibility is driven by the `hidden` attribute rather than a CSS display rule, so a JavaScript
    // failure leaves a menu simply closed rather than stuck open. Every menu action (language link,
    // logout submit) causes a full page navigation, which already satisfies "closes after selection"
    // without extra code here. Only one of the two header dropdowns is ever open at a time: opening
    // one closes the other.
    const dropdowns = [
        { triggerId: "header-language-trigger", menuId: "header-language-menu" },
        { triggerId: "header-account-trigger", menuId: "header-account-menu" },
    ]
        .map(({ triggerId, menuId }) => ({
            trigger: document.getElementById(triggerId),
            menu: document.getElementById(menuId),
        }))
        .filter(({ trigger, menu }) => trigger && menu);

    if (!dropdowns.length) {
        return;
    }

    const isOpen = (dropdown) => dropdown.trigger.getAttribute("aria-expanded") === "true";

    const setOpen = (dropdown, open) => {
        dropdown.trigger.setAttribute("aria-expanded", open ? "true" : "false");
        dropdown.menu.hidden = !open;
    };

    const closeOthers = (except) => {
        dropdowns.forEach((dropdown) => {
            if (dropdown !== except) {
                setOpen(dropdown, false);
            }
        });
    };

    dropdowns.forEach((dropdown) => {
        dropdown.trigger.addEventListener("click", () => {
            const nextOpen = !isOpen(dropdown);
            closeOthers(dropdown);
            setOpen(dropdown, nextOpen);
        });
    });

    document.addEventListener("click", (event) => {
        dropdowns.forEach((dropdown) => {
            if (isOpen(dropdown) && !dropdown.trigger.contains(event.target) && !dropdown.menu.contains(event.target)) {
                setOpen(dropdown, false);
            }
        });
    });

    document.addEventListener("keydown", (event) => {
        if (event.key !== "Escape") {
            return;
        }
        dropdowns.forEach((dropdown) => {
            if (isOpen(dropdown)) {
                setOpen(dropdown, false);
                dropdown.trigger.focus();
            }
        });
    });
})();
