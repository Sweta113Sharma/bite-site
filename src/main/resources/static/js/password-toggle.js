document.addEventListener('click', function (event) {
    const button = event.target.closest('.toggle-password');
    if (!button) {
        return;
    }
    const input = document.getElementById(button.dataset.target);
    if (!input) {
        return;
    }

    // Toggle the field first, and never gate it on finding the icon. The previous
    // version bailed out when its `querySelector('i')` came back null, so when the
    // markup moved from Phosphor <i> to a Material Symbols <span> the whole button
    // stopped working — a cosmetic detail had been made load-bearing.
    const showing = input.type === 'text';
    input.type = showing ? 'password' : 'text';
    button.setAttribute('aria-label', showing ? 'Show password' : 'Hide password');

    // Icon is best-effort. Both spellings are handled so this keeps working whichever
    // set a given page happens to render.
    const symbol = button.querySelector('.material-symbols-outlined');
    if (symbol) {
        symbol.textContent = showing ? 'visibility' : 'visibility_off';
        return;
    }
    const phosphor = button.querySelector('i');
    if (phosphor) {
        phosphor.classList.toggle('ph-eye', showing);
        phosphor.classList.toggle('ph-eye-slash', !showing);
    }
});
