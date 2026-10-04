// Tab shortcuts: Ctrl+1 / Ctrl+2 / Ctrl+3 activate the tab link carrying the matching data-shortcut value by clicking
// it, so the existing tab link (route, permissions, query handling) stays the single navigation mechanism. The mapping
// lives in each page's markup (Front Desk, Checkout Review) and the script is loaded only by those templates. A tab
// that is not rendered (no permission) has no shortcut, which is then left to the browser.
(function () {
    'use strict';

    var tabs = document.querySelectorAll('a[data-shortcut]');
    if (!tabs.length) {
        return;
    }

    function isEditable(element) {
        if (!(element instanceof Element)) {
            return false;
        }
        return element.isContentEditable || element.closest('input, textarea, select, [contenteditable=""], [contenteditable="true"]') !== null;
    }

    document.addEventListener('keydown', function (event) {
        if (event.defaultPrevented || event.repeat || !event.ctrlKey || event.altKey || event.shiftKey || event.metaKey) {
            return;
        }
        if (isEditable(event.target) || isEditable(document.activeElement)) {
            return;
        }
        if (document.querySelector('dialog[open]') || (event.target instanceof Element && event.target.closest('dialog, [role="dialog"]'))) {
            return;
        }
        var tab = document.querySelector('a[data-shortcut="' + event.key + '"]');
        if (!tab) {
            return;
        }
        event.preventDefault();
        tab.click();
    });
})();
