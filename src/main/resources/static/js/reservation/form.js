(() => {
    const roomRows = document.getElementById("room-rows");
    const template = document.getElementById("room-row-template");
    const addRoomButton = document.getElementById("add-room");
    const guestVerification = document.querySelector("[data-guest-verification]");

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

    if (!roomRows || !template || !addRoomButton) {
        initializeGuestVerification();
        return;
    }

    const updateRoomFieldNames = () => {
        roomRows.querySelectorAll(".room-row").forEach((roomRow, index) => {
            const roomSelect = roomRow.querySelector("select");
            const nightlyRateInput = roomRow.querySelector("[data-nightly-rate], .js-money-input");
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
    });

    updateRoomFieldNames();
    initializeGuestVerification();
})();
