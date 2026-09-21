(() => {
    const analyticsData = document.getElementById("dashboard-analytics-data");

    if (!analyticsData || typeof Chart === "undefined") {
        return;
    }

    let analytics;
    try {
        analytics = JSON.parse(analyticsData.textContent);
    } catch {
        return;
    }

    const monthLabels = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    ];

    const showEmptyState = (chartId) => {
        const emptyState = document.querySelector(`[data-chart-empty="${chartId}"]`);
        if (emptyState) {
            emptyState.hidden = false;
        }
    };

    const renderChart = (chartId, type, labels, values, colors) => {
        const canvas = document.getElementById(chartId);
        if (!canvas) {
            return;
        }

        if (!values.some((value) => value > 0)) {
            canvas.hidden = true;
            showEmptyState(chartId);
            return;
        }

        new Chart(canvas, {
            type,
            data: {
                labels,
                datasets: [{
                    backgroundColor: colors,
                    borderColor: type === "line" ? "#1769aa" : "#ffffff",
                    borderWidth: type === "line" ? 2 : 1,
                    data: values,
                    fill: false,
                    label: "Count"
                }]
            },
            options: {
                maintainAspectRatio: false,
                plugins: {
                    legend: { display: type === "doughnut" },
                    tooltip: { enabled: true }
                },
                scales: type === "doughnut" ? {} : {
                    y: {
                        beginAtZero: true,
                        ticks: { precision: 0 }
                    }
                }
            }
        });
    };

    renderChart(
        "reservations-by-month-chart",
        "bar",
        monthLabels,
        analytics.reservationsByCheckInMonth.map((item) => item.count),
        "#1769aa");

    renderChart(
        "booked-rooms-by-type-chart",
        "bar",
        analytics.bookedRoomsByRoomType.map((item) => `${item.roomTypeCode} — ${item.roomTypeName}`),
        analytics.bookedRoomsByRoomType.map((item) => item.count),
        "#2f855a");

    renderChart(
        "reservations-by-source-chart",
        "doughnut",
        analytics.reservationsBySource.map((item) => item.source),
        analytics.reservationsBySource.map((item) => item.count),
        ["#1769aa", "#2f855a", "#d97706", "#7c3aed"]);
})();
