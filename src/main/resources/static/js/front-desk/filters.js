// Front Desk filter bar: choosing a filter reloads the list from the server, so the query-driven result stays the
// single source of truth. The form is a plain GET, so it still works without this script (search submits on Enter).
(function () {
    'use strict';

    var form = document.getElementById('front-desk-filters');
    if (!form) {
        return;
    }

    form.querySelectorAll('select').forEach(function (select) {
        select.addEventListener('change', function () {
            form.requestSubmit();
        });
    });
})();
