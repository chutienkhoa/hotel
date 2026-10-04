(() => {
    // Login screen: password show/hide toggle. Presentation only; the form itself is a plain
    // Spring Security form-login POST and works unchanged without this script.
    const toggle = document.querySelector("[data-password-toggle]");
    const input = toggle && document.getElementById(toggle.dataset.passwordToggle);
    if (!toggle || !input) {
        return;
    }

    toggle.addEventListener("click", () => {
        const reveal = input.type === "password";
        input.type = reveal ? "text" : "password";
        toggle.setAttribute("aria-pressed", reveal ? "true" : "false");
        toggle.setAttribute("aria-label", reveal ? toggle.dataset.labelHide : toggle.dataset.labelShow);
    });
})();
