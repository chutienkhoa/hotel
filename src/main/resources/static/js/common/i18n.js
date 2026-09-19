(function () {
    "use strict";

    /**
     * Reads a translated string that the server rendered into the page head as
     * <meta name="pms-i18n:KEY" content="...">. Falls back to the supplied English text.
     */
    const t = (key, fallback) => {
        const meta = document.querySelector('meta[name="pms-i18n:' + key + '"]');
        return meta && meta.content ? meta.content : fallback;
    };

    window.PmsI18n = { t: t };
})();
