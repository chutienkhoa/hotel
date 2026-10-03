(() => {
    // Desktop sidebar collapse. The state lives on <html> as .sidebar-collapsed, which layout.css uses to narrow
    // the .app-shell column (the content column then takes the freed width). The class is applied here, in a
    // synchronous head script, so a remembered collapsed state never paints expanded first. Wiring the button
    // waits for the DOM. The state is per-browser only (localStorage); nothing is sent to the server.
    const STORAGE_KEY = "pms.sidebar.collapsed";
    const COLLAPSED_CLASS = "sidebar-collapsed";
    const root = document.documentElement;

    const readStoredCollapsed = () => {
        try {
            return localStorage.getItem(STORAGE_KEY) === "true";
        } catch (error) {
            // Storage can be blocked (private mode, disabled site data); the sidebar then just starts expanded.
            return false;
        }
    };

    const storeCollapsed = (collapsed) => {
        try {
            localStorage.setItem(STORAGE_KEY, collapsed ? "true" : "false");
        } catch (error) {
            // Not remembered across navigation/reload in this browser; the toggle still works for this page.
        }
    };

    if (readStoredCollapsed()) {
        root.classList.add(COLLAPSED_CLASS);
    }

    const wireSidebarCollapse = () => {
        const toggle = document.getElementById("sidebar-collapse-toggle");
        const sidebar = document.getElementById("sidebar-nav");
        if (!toggle || !sidebar) {
            return;
        }

        // Labels are visually hidden (not display:none) while collapsed, so the link keeps its accessible name.
        // The native title attribute is the tooltip: the sidebar's overflow clips any custom popover, and a title
        // only exists while collapsed so expanded labels are not repeated as tooltips.
        const syncTooltips = (collapsed) => {
            sidebar.querySelectorAll(".sidebar-nav-link").forEach((link) => {
                const label = link.querySelector(".sidebar-nav-label");
                if (collapsed && label) {
                    link.setAttribute("title", label.textContent.trim());
                } else {
                    link.removeAttribute("title");
                }
            });
        };

        const applyCollapsed = (collapsed) => {
            root.classList.toggle(COLLAPSED_CLASS, collapsed);
            // aria-expanded describes the sidebar, so it is "true" while the sidebar is expanded.
            toggle.setAttribute("aria-expanded", collapsed ? "false" : "true");
            toggle.setAttribute("aria-label", collapsed ? toggle.dataset.labelExpand : toggle.dataset.labelCollapse);
            syncTooltips(collapsed);
        };

        applyCollapsed(root.classList.contains(COLLAPSED_CLASS));
        toggle.addEventListener("click", () => {
            const nextCollapsed = !root.classList.contains(COLLAPSED_CLASS);
            storeCollapsed(nextCollapsed);
            applyCollapsed(nextCollapsed);
        });
    };

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", wireSidebarCollapse);
    } else {
        wireSidebarCollapse();
    }
})();
