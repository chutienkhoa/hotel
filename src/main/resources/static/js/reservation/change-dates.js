(() => {
    "use strict";

    // Change Stay Dates: wires the shared Stay range picker (the same one Create Reservation and the Reservation List
    // use). The picker is only the UI for the hidden newCheckInDate / newCheckOutDate values; the server validates them.
    // A new range must always exist here, so the picker's Clear button is not offered.
    const root = document.querySelector("[data-change-dates-picker]");
    if (!root || !window.PmsStayRangePicker) {
        return;
    }
    const picker = window.PmsStayRangePicker.init(root, {});
    const clear = root.querySelector("[data-stay-clear]");
    if (picker && clear) {
        clear.style.display = "none";
    }
})();
