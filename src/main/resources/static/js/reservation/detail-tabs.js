(() => {
    const tabs = document.querySelectorAll(".detail-tabs .detail-tab");
    if (!tabs.length) return;
    const panels = document.querySelectorAll(".detail-tab-panel");

    const activate = (tab) => {
        tabs.forEach((other) => {
            const isActive = other === tab;
            other.classList.toggle("detail-tab--active", isActive);
            other.setAttribute("aria-selected", String(isActive));
        });
        const targetId = tab.dataset.tabPanel;
        panels.forEach((panel) => {
            panel.hidden = panel.id !== targetId;
        });
        const scrollId = tab.dataset.tabScroll;
        if (!scrollId) return;
        const scrollTarget = document.getElementById(scrollId);
        if (scrollTarget) scrollTarget.scrollIntoView({ behavior: "smooth", block: "start" });
    };

    tabs.forEach((tab) => tab.addEventListener("click", () => activate(tab)));
})();
