// Charges and Payments table sorting (the same behavior for both). Rows are reordered in place, so charge selection (keyed by charge id, not row position)
// and the Charge Detail panel are unaffected. Sort values come from the data-sort-* attributes the server renders,
// never from formatted display text. Missing values always sort last, in either direction.
(() => {
    const table = document.querySelector("table.folio-table--charges, table.folio-table--payments");
    if (!table) return;

    const tbody = table.tBodies[0];
    const sortButtons = table.querySelectorAll("[data-sort-key]");
    const NUMERIC_KEYS = new Set(["date", "qty", "price", "amount", "applied"]);
    const ATTRIBUTE_BY_KEY = {
        date: "sortDate",
        type: "sortType",
        method: "sortMethod",
        reference: "sortReference",
        currency: "sortCurrency",
        applied: "sortApplied",
        qty: "sortQty",
        price: "sortPrice",
        amount: "sortAmount",
        status: "sortStatus",
        added: "sortAdded",
    };
    const ARROW_BY_DIRECTION = { ascending: "↑", descending: "↓" };
    const IDLE_ICON = "↕";

    let activeKey = "date";
    let direction = "ascending";

    const compareValues = (a, b) => (NUMERIC_KEYS.has(activeKey) ? Number(a) - Number(b) : a.localeCompare(b));

    const sortRows = () => {
        const attribute = ATTRIBUTE_BY_KEY[activeKey];
        const sign = direction === "ascending" ? 1 : -1;
        const rows = Array.from(tbody.rows);
        rows.sort((rowA, rowB) => {
            const a = rowA.dataset[attribute] ?? "";
            const b = rowB.dataset[attribute] ?? "";
            if (a === "" || b === "") return (a === "") - (b === "");
            return sign * compareValues(a, b);
        });
        rows.forEach((row) => tbody.appendChild(row));
    };

    const updateIndicators = () => {
        sortButtons.forEach((button) => {
            const isActive = button.dataset.sortKey === activeKey;
            button.closest("th").setAttribute("aria-sort", isActive ? direction : "none");
            button.querySelector(".folio-sort__icon").textContent = isActive ? ARROW_BY_DIRECTION[direction] : IDLE_ICON;
        });
    };

    sortButtons.forEach((button) => {
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

    sortRows();
    updateIndicators();
})();
