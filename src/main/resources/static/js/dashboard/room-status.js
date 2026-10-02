(() => {
    const dataElement = document.getElementById("dashboard-room-status-data");
    const canvas = document.getElementById("room-status-donut");

    if (!dataElement || !canvas || typeof Chart === "undefined") {
        return;
    }

    let data;
    try {
        data = JSON.parse(dataElement.textContent);
    } catch {
        return;
    }

    const STATUS_COLORS = {
        AVAILABLE: "#05603a",
        OCCUPIED: "#185fa5",
        DIRTY: "#8a4b08",
        CLEANING: "#08798c",
        MAINTENANCE: "#a04300",
        OUT_OF_ORDER: "#b42318"
    };

    const rows = Array.isArray(data.roomsByStatus) ? data.roomsByStatus : [];
    const values = rows.map((row) => row.count);

    if (!values.some((value) => value > 0)) {
        return;
    }

    new Chart(canvas, {
        type: "doughnut",
        data: {
            labels: rows.map((row) => row.status),
            datasets: [{
                backgroundColor: rows.map((row) => STATUS_COLORS[row.status] || "#9fb3c8"),
                borderColor: "#ffffff",
                borderWidth: 2,
                data: values,
                label: typeof data.chartLabel === "string" ? data.chartLabel : "Rooms"
            }]
        },
        options: {
            cutout: "70%",
            maintainAspectRatio: false,
            plugins: {
                legend: { display: false },
                tooltip: { enabled: true }
            }
        }
    });
})();
