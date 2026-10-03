(() => {
    // Task33 header clock. Shows the browser's own local time as HH:mm:ss (never server or database time).
    // It renders at load, then re-renders at each second boundary, so the displayed time stays current
    // without a page refresh. The element is not a live region on purpose: announcing it every second
    // would be noise for screen-reader users.
    const clock = document.getElementById("header-clock");
    const time = document.getElementById("header-clock-time");
    if (!clock || !time) {
        return;
    }

    const render = () => {
        const now = new Date();
        const hours = String(now.getHours()).padStart(2, "0");
        const minutes = String(now.getMinutes()).padStart(2, "0");
        const seconds = String(now.getSeconds()).padStart(2, "0");
        const text = `${hours}:${minutes}:${seconds}`;
        time.textContent = text;
        time.dateTime = text;
        clock.hidden = false;
    };

    // Schedule the next render for the next full second, then keep re-arming after each one.
    const scheduleNextSecond = () => {
        const msIntoSecond = new Date().getMilliseconds();
        window.setTimeout(() => {
            render();
            scheduleNextSecond();
        }, 1000 - msIntoSecond);
    };

    // Background tabs throttle timers, so catch up as soon as the page is visible again.
    document.addEventListener("visibilitychange", () => {
        if (!document.hidden) {
            render();
        }
    });

    render();
    scheduleNextSecond();
})();
