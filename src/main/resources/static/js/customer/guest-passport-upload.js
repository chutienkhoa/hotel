(() => {
    const inputSelector = ".js-passport-images-input";
    const warningSelector = ".js-passport-size-warning";
    const MAX_PASSPORT_IMAGE_SIZE = 5 * 1024 * 1024;
    const OVERSIZED_MESSAGE = "Each passport image must be 5 MB or smaller.";

    const supportsFileRemoval = () => {
        try {
            return typeof DataTransfer !== "undefined";
        } catch {
            return false;
        }
    };

    const removeFileAt = (input, list, warning, indexToRemove) => {
        const dataTransfer = new DataTransfer();
        Array.from(input.files)
                .filter((_, index) => index !== indexToRemove)
                .forEach((file) => dataTransfer.items.add(file));
        input.files = dataTransfer.files;
        renderSelectedFiles(input, list, warning);
    };

    const hasOversizedFile = (input) => Array.from(input.files).some((file) => file.size > MAX_PASSPORT_IMAGE_SIZE);

    const updateWarning = (input, warning) => {
        if (!warning) {
            return;
        }
        if (hasOversizedFile(input)) {
            warning.textContent = OVERSIZED_MESSAGE;
            warning.hidden = false;
        } else {
            warning.textContent = "";
            warning.hidden = true;
        }
    };

    const renderSelectedFiles = (input, list, warning) => {
        list.replaceChildren();
        Array.from(input.files).forEach((file, index) => {
            const item = document.createElement("li");
            item.className = "selected-file-item";
            if (file.size > MAX_PASSPORT_IMAGE_SIZE) {
                item.classList.add("selected-file-item--invalid");
            }

            const name = document.createElement("span");
            name.className = "selected-file-name";
            name.textContent = file.name;
            item.appendChild(name);

            if (supportsFileRemoval()) {
                const removeButton = document.createElement("button");
                removeButton.type = "button";
                removeButton.className = "selected-file-remove";
                removeButton.textContent = "Remove";
                removeButton.addEventListener("click", () => removeFileAt(input, list, warning, index));
                item.appendChild(removeButton);
            }

            list.appendChild(item);
        });
        updateWarning(input, warning);
    };

    const preventSubmissionWhenOversized = (input) => {
        const form = input.form;
        if (!form) {
            return;
        }
        form.addEventListener("submit", (event) => {
            if (hasOversizedFile(input)) {
                event.preventDefault();
            }
        });
    };

    const initializeInput = (input) => {
        const list = document.createElement("ul");
        list.className = "selected-file-list";
        input.insertAdjacentElement("afterend", list);
        const warning = input.closest(".form-field")?.querySelector(warningSelector) ?? null;
        input.addEventListener("change", () => renderSelectedFiles(input, list, warning));
        preventSubmissionWhenOversized(input);
    };

    const initialize = () => {
        document.querySelectorAll(inputSelector).forEach(initializeInput);
    };

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", initialize);
    } else {
        initialize();
    }
})();
