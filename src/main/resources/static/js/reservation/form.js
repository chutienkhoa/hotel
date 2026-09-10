(() => {
    const roomRows = document.getElementById("room-rows");
    const template = document.getElementById("room-row-template");
    const addRoomButton = document.getElementById("add-room");

    if (!roomRows || !template || !addRoomButton) {
        return;
    }

    const updateRoomFieldNames = () => {
        roomRows.querySelectorAll(".room-row").forEach((roomRow, index) => {
            const roomSelect = roomRow.querySelector("select");
            const nightlyRateInput = roomRow.querySelector("input[type='number']");
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
})();
