(() => {
    "use strict";

    // Reservation Detail Activity Log column sorting. Rows are re-ordered in place from the data-sort-* values the
    // server renders (epoch milliseconds, actor, label, status code), never from formatted text or badge markup.
    // Clicking a new column sorts ascending; clicking the active column toggles ascending/descending. A missing value
    // always sorts last, and equal values keep the server's order (newest first), so the result is deterministic.
    const table = document.querySelector("table[data-activity-sortable]");
    if (!table || !table.tBodies[0]) {
        return;
    }

    const tbody = table.tBodies[0];
    const buttons = Array.from(table.querySelectorAll("[data-sort-key]"));
    const ATTRIBUTE_BY_KEY = { time: "sortTime", actor: "sortActor", action: "sortAction", status: "sortStatus" };
    const collator = new Intl.Collator(document.documentElement.lang || undefined, { sensitivity: "base" });
    const serverOrder = new Map(Array.from(tbody.rows).map((row, index) => [row, index]));

    let activeKey = "time";
    let direction = "descending";

    const compareValues = (a, b) => (activeKey === "time" ? Number(a) - Number(b) : collator.compare(a, b));

    const sortRows = () => {
        const attribute = ATTRIBUTE_BY_KEY[activeKey];
        const sign = direction === "ascending" ? 1 : -1;
        const rows = Array.from(tbody.rows);
        rows.sort((rowA, rowB) => {
            const a = rowA.dataset[attribute] ?? "";
            const b = rowB.dataset[attribute] ?? "";
            const byValue = a === "" || b === "" ? (a === "") - (b === "") : sign * compareValues(a, b);
            return byValue || serverOrder.get(rowA) - serverOrder.get(rowB);
        });
        rows.forEach((row) => tbody.appendChild(row));
    };

    const updateIndicators = () => {
        buttons.forEach((button) => {
            const isActive = button.dataset.sortKey === activeKey;
            const state = !isActive ? "neutral" : direction === "ascending" ? "asc" : "desc";
            const header = button.closest("th");
            header.setAttribute("aria-sort", isActive ? direction : "none");
            header.classList.toggle("rd-sortable--active", isActive);
            button.querySelectorAll("[data-sort-icon]").forEach((icon) => {
                icon.hidden = icon.dataset.sortIcon !== state;
            });
        });
    };

    buttons.forEach((button) => {
        button.addEventListener("click", () => {
            const key = button.dataset.sortKey;
            if (key === activeKey) {
                direction = direction === "ascending" ? "descending" : "ascending";
            } else {
                activeKey = key;
                direction = "ascending";
            }
            sortRows();
            updateIndicators();
        });
    });
})();
