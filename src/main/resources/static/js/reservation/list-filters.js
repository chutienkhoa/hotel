// Reservation List filters: Status and Source reload the list as soon as they change, and the Stay date control is the
// shared range picker (static/js/common/stay-range-picker.js). The form is a plain GET, so the server's query stays the
// single source of truth; the dates sent are the picker's ISO values and the server validates them again.
(function () {
    "use strict";

    var form = document.getElementById("reservation-list-filters");
    if (!form) {
        return;
    }

    form.querySelectorAll("select").forEach(function (select) {
        select.addEventListener("change", function () {
            form.requestSubmit();
        });
    });

    if (!window.PmsStayRangePicker) {
        return;
    }
    window.PmsStayRangePicker.init(form.querySelector("[data-stay-picker]"), {
        onApply: function () {
            form.requestSubmit();
        },
        onClear: function (hadRange) {
            if (hadRange) {
                form.requestSubmit();
            }
        }
    });
})();
