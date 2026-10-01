(() => {
    const checkOutInput = document.getElementById("checkOutDate");
    const roomRows = document.getElementById("room-rows");
    const addRoomButton = document.getElementById("add-room");
    const emptyState = document.getElementById("room-availability-empty");
    if (!checkOutInput || !roomRows || !addRoomButton) return;

    let availableRooms = [];

    const renderRoomOptions = (select) => {
        const previous = select.value;
        select.innerHTML = "";
        const placeholder = document.createElement("option");
        placeholder.value = "";
        placeholder.textContent = availableRooms.length ? "Select a room" : "No rooms available for these dates";
        select.appendChild(placeholder);
        availableRooms.forEach((room) => {
            const option = document.createElement("option");
            option.value = room.id;
            option.textContent = room.roomNumber;
            select.appendChild(option);
        });
        if (previous && availableRooms.some((room) => room.id === previous)) {
            select.value = previous;
        }
    };

    const refreshAllRowSelects = () => {
        roomRows.querySelectorAll("select[data-room-select]").forEach(renderRoomOptions);
        addRoomButton.disabled = availableRooms.length === 0;
        if (emptyState) {
            emptyState.hidden = !(checkOutInput.value && availableRooms.length === 0);
        }
    };

    const fetchAvailableRooms = () => {
        if (!checkOutInput.value) {
            availableRooms = [];
            refreshAllRowSelects();
            return;
        }
        fetch(`/check-in/walk-in/available-rooms?checkOutDate=${encodeURIComponent(checkOutInput.value)}`)
            .then((response) => (response.ok ? response.json() : []))
            .then((rooms) => {
                availableRooms = rooms;
                refreshAllRowSelects();
            })
            .catch(() => {
                availableRooms = [];
                refreshAllRowSelects();
            });
    };

    const updateFieldNames = () => {
        roomRows.querySelectorAll(".room-row").forEach((row, index) => {
            const select = row.querySelector("select[data-room-select]");
            const rate = row.querySelector("[data-nightly-rate]");
            select.name = `rooms[${index}].roomId`;
            rate.name = `rooms[${index}].nightlyRate`;
        });
    };

    const buildRoomRow = () => {
        const row = document.createElement("div");
        row.className = "room-row";
        row.innerHTML =
            '<div class="room-fields-three-column-grid">' +
            '<div class="form-field"><label>Room<select data-room-select required></select></label></div>' +
            '<div class="form-field"><label>Nightly rate' +
            '<input class="js-money-input" data-nightly-rate inputmode="decimal" required type="text" /></label></div>' +
            '<div aria-hidden="true" class="room-currency-spacer"></div>' +
            "</div>" +
            '<button class="button button-danger remove-room" type="button">Remove</button>';
        return row;
    };

    addRoomButton.addEventListener("click", () => {
        if (availableRooms.length === 0) return;
        const row = buildRoomRow();
        roomRows.appendChild(row);
        renderRoomOptions(row.querySelector("select[data-room-select]"));
        updateFieldNames();
    });

    roomRows.addEventListener("click", (event) => {
        if (event.target.matches(".remove-room") && roomRows.children.length > 1) {
            event.target.closest(".room-row").remove();
            updateFieldNames();
        }
    });

    checkOutInput.addEventListener("change", fetchAvailableRooms);
    updateFieldNames();
    fetchAvailableRooms();
})();
