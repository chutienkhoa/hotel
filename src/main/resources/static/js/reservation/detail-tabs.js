(() => {
    "use strict";

    // Reservation Detail tabs. A tab with data-detail-tab swaps in the panel "panel-<key>"; Folio and Payments are
    // plain links to the Folio page and are not handled here. A control with data-detail-tab-target (for example
    // Recent Activity's "View All") activates the named tab.
    const tabs = Array.from(document.querySelectorAll("[data-detail-tab]"));
    if (tabs.length === 0) {
        return;
    }

    const panelFor = (key) => document.getElementById(`panel-${key}`);

    const activate = (tab, moveFocus) => {
        tabs.forEach((other) => {
            const active = other === tab;
            other.classList.toggle("rd-tab--active", active);
            other.setAttribute("aria-selected", String(active));
            const panel = panelFor(other.dataset.detailTab);
            if (panel) {
                panel.hidden = !active;
            }
        });
        if (moveFocus) {
            tab.focus();
            tab.scrollIntoView({ block: "nearest" });
        }
    };

    tabs.forEach((tab) => tab.addEventListener("click", () => activate(tab, false)));

    document.querySelectorAll("[data-detail-tab-target]").forEach((control) => {
        control.addEventListener("click", () => {
            const target = tabs.find((tab) => tab.dataset.detailTab === control.dataset.detailTabTarget);
            if (target) {
                activate(target, true);
            }
        });
    });
})();
