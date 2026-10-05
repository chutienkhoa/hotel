(() => {
    "use strict";

    // Reservation Detail "More" menu: a disclosure menu that closes on selection, Escape or an outside click, keeps
    // focus inside while open (arrow keys, Home, End) and stays inside the viewport.
    const menus = Array.from(document.querySelectorAll("[data-detail-menu]"));

    const parts = (menu) => ({
        toggle: menu.querySelector("[data-detail-menu-toggle]"),
        panel: menu.querySelector("[role='menu']"),
    });
    const items = (panel) => Array.from(panel.querySelectorAll("[role='menuitem']"));

    const close = (menu, returnFocus) => {
        const { toggle, panel } = parts(menu);
        if (!toggle || !panel || panel.hidden) {
            return;
        }
        panel.hidden = true;
        toggle.setAttribute("aria-expanded", "false");
        if (returnFocus) {
            toggle.focus();
        }
    };

    const keepInsideViewport = (panel) => {
        panel.classList.remove("rd-menu__panel--start");
        if (panel.getBoundingClientRect().left < 8) {
            panel.classList.add("rd-menu__panel--start");
        }
    };

    const open = (menu) => {
        const { toggle, panel } = parts(menu);
        if (!toggle || !panel) {
            return;
        }
        panel.hidden = false;
        toggle.setAttribute("aria-expanded", "true");
        keepInsideViewport(panel);
        const first = items(panel)[0];
        if (first) {
            first.focus();
        }
    };

    menus.forEach((menu) => {
        const { toggle, panel } = parts(menu);
        if (!toggle || !panel) {
            return;
        }
        toggle.addEventListener("click", () => (panel.hidden ? open(menu) : close(menu, false)));
        toggle.addEventListener("keydown", (event) => {
            if (event.key === "ArrowDown" && panel.hidden) {
                event.preventDefault();
                open(menu);
            }
        });
        panel.addEventListener("click", (event) => {
            if (event.target instanceof Element && event.target.closest("[role='menuitem']")) {
                close(menu, false);
            }
        });
        panel.addEventListener("keydown", (event) => {
            const list = items(panel);
            const index = list.indexOf(document.activeElement);
            if (event.key === "ArrowDown") {
                event.preventDefault();
                list[(index + 1) % list.length].focus();
            } else if (event.key === "ArrowUp") {
                event.preventDefault();
                list[(index - 1 + list.length) % list.length].focus();
            } else if (event.key === "Home") {
                event.preventDefault();
                list[0].focus();
            } else if (event.key === "End") {
                event.preventDefault();
                list[list.length - 1].focus();
            } else if (event.key === "Escape") {
                event.preventDefault();
                close(menu, true);
            } else if (event.key === "Tab") {
                close(menu, false);
            }
        });
    });

    document.addEventListener("click", (event) => {
        menus.forEach((menu) => {
            if (!(event.target instanceof Node) || !menu.contains(event.target)) {
                close(menu, false);
            }
        });
    });
    window.addEventListener("resize", () => menus.forEach((menu) => close(menu, false)));
})();
