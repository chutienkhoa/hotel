(() => {
    const datePickerSelector = ".js-date-picker";

    const addTodayAction = (instance) => {
        const footer = document.createElement("div");
        const clearButton = document.createElement("button");
        const todayButton = document.createElement("button");

        footer.className = "date-picker__footer";
        clearButton.className = "date-picker__clear";
        clearButton.type = "button";
        clearButton.textContent = "Clear";
        clearButton.setAttribute("aria-label", "Clear date");
        clearButton.addEventListener("click", () => {
            instance.clear(true);
            instance.close();
        });
        todayButton.className = "date-picker__today";
        todayButton.type = "button";
        todayButton.textContent = "Today";
        todayButton.setAttribute("aria-label", "Select today");
        todayButton.addEventListener("click", () => {
            instance.setDate(new Date(), true);
            instance.close();
        });

        footer.append(clearButton, todayButton);
        instance.calendarContainer.append(footer);
    };

    const associateAlternateInputWithLabel = (input, instance) => {
        if (!input.id || !instance.altInput) {
            return;
        }

        const alternateInputId = `${input.id}-display`;
        const label = document.querySelector(`label[for="${input.id}"]`);

        instance.altInput.id = alternateInputId;
        if (label) {
            label.htmlFor = alternateInputId;
        }
    };

    const initializeDatePickers = () => {
        if (typeof window.flatpickr !== "function") {
            return;
        }

        document.querySelectorAll(datePickerSelector).forEach((input) => {
            window.flatpickr(input, {
                altFormat: "d/m/Y",
                altInput: true,
                allowInput: false,
                dateFormat: "Y-m-d",
                disableMobile: true,
                onReady: (_selectedDates, _dateString, instance) => {
                    associateAlternateInputWithLabel(input, instance);
                    addTodayAction(instance);
                }
            });
        });
    };

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initializeDatePickers);
    } else {
        initializeDatePickers();
    }
})();
