(() => {
    const roomRows = document.getElementById("room-rows");
    const template = document.getElementById("room-row-template");
    const addRoomButton = document.getElementById("add-room");
    const guestVerification = document.querySelector("[data-guest-verification]");

    const initializeOtaBookingReferenceToggle = () => {
        const sourceSelect = document.getElementById("source");
        const otaField = document.querySelector("[data-ota-booking-reference-field]");
        const otaInput = document.getElementById("otaBookingReference");
        if (!sourceSelect || !otaField || !otaInput) return;
        const update = () => {
            const isDirectOrUnset = sourceSelect.value === "" || sourceSelect.value === "DIRECT";
            otaField.hidden = isDirectOrUnset;
            otaInput.required = !isDirectOrUnset;
        };
        sourceSelect.addEventListener("change", update);
        update();
    };
    initializeOtaBookingReferenceToggle();

    const initializeGuestVerification = () => {
        if (!guestVerification) return;
        const select = guestVerification.querySelector("[data-guest-select]");
        const information = document.querySelector("[data-guest-information]");
        const update = () => {
            const option = select.options[select.selectedIndex];
            information.hidden = !option?.value;
            if (!option?.value) return;
            information.open = false;
            information.querySelector("[data-guest-code]").textContent = option.dataset.guestCode || "—";
            information.querySelector("[data-guest-full-name]").textContent = option.dataset.fullName || "—";
            information.querySelector("[data-guest-email]").textContent = option.dataset.email || "—";
            information.querySelector("[data-guest-phone]").textContent = option.dataset.phone || "—";
            information.querySelector("[data-guest-nationality]").textContent = option.dataset.nationality || "—";
        };
        select.addEventListener("change", update);
        update();
    };


    const initializeAccompanyingGuests = () => {
        const container = document.querySelector("[data-accompanying-guests]");
        if (!container) return;
        const select = container.querySelector("[data-accompanying-select]");
        const addButton = container.querySelector("[data-accompanying-add]");
        const list = container.querySelector("[data-accompanying-list]");
        const empty = container.querySelector("[data-accompanying-empty]");
        const error = container.querySelector("[data-accompanying-error]");
        const template = container.querySelector("[data-accompanying-template]");
        const primarySelect = document.querySelector("[data-guest-select]");
        const selectedIds = () => Array.from(list.querySelectorAll("li")).map((item) => item.dataset.guestId);
        const showError = (message) => {
            error.textContent = message || "";
            error.hidden = !message;
        };
        const refreshEmpty = () => {
            empty.hidden = list.children.length > 0;
        };
        const checkPrimary = () => {
            const primaryId = primarySelect ? primarySelect.value : "";
            showError(primaryId && selectedIds().includes(primaryId) ? container.dataset.messagePrimary : "");
        };
        addButton.addEventListener("click", () => {
            const guestId = select.value;
            if (!guestId) return;
            if (primarySelect && primarySelect.value === guestId) {
                showError(container.dataset.messagePrimary);
                return;
            }
            if (selectedIds().includes(guestId)) {
                showError(container.dataset.messageDuplicate);
                return;
            }
            showError("");
            const item = template.content.firstElementChild.cloneNode(true);
            item.dataset.guestId = guestId;
            item.querySelector("[data-accompanying-label]").textContent = select.options[select.selectedIndex].textContent;
            const input = item.querySelector("input");
            input.value = guestId;
            input.disabled = false;
            list.appendChild(item);
            select.value = "";
            refreshEmpty();
        });
        list.addEventListener("click", (event) => {
            const remove = event.target.closest("[data-accompanying-remove]");
            if (!remove) return;
            remove.closest("li").remove();
            refreshEmpty();
            checkPrimary();
        });
        if (primarySelect) {
            primarySelect.addEventListener("change", checkPrimary);
        }
        refreshEmpty();
    };
    initializeAccompanyingGuests();

    if (!roomRows || !template || !addRoomButton) {
        initializeGuestVerification();
        return;
    }

    const updateRoomFieldNames = () => {
        roomRows.querySelectorAll(".room-row").forEach((roomRow, index) => {
            const roomSelect = roomRow.querySelector("select");
            const nightlyRateInput = roomRow.querySelector("[data-nightly-rate], [data-numeric=\"vnd\"]");
            roomSelect.name = `rooms[${index}].roomId`;
            nightlyRateInput.name = `rooms[${index}].nightlyRate`;
        });
    };

    const removeRoomRow = (event) => {
        const roomRow = event.target.closest(".room-row");
        if (roomRows.children.length > 1) {
            roomRow.remove();
            updateRoomFieldNames();
        }
    };

    roomRows.addEventListener("click", (event) => {
        if (event.target.matches(".remove-room")) {
            removeRoomRow(event);
        }
    });

    addRoomButton.addEventListener("click", () => {
        const roomRow = template.content.cloneNode(true);
        roomRows.appendChild(roomRow);
        updateRoomFieldNames();
        if (window.PmsNumericInput) {
            roomRows.querySelectorAll("[data-numeric]").forEach(window.PmsNumericInput.initialize);
        }
    });

    updateRoomFieldNames();
    initializeGuestVerification();
})();
