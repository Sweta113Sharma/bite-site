/* Show/hide for password fields.
 *
 * Delegated from document rather than bound per button, so a field rendered after load
 * still works and no page has to remember to initialise anything.
 *
 * Every page carrying a .toggle-password button must also load this script.
 * account/change-password.html did not, so the eye there was decorative from the day it
 * was added — PasswordToggleWiringTest now fails the build on that. */
document.addEventListener('click', function (event) {
    const button = event.target.closest('.toggle-password');
    if (!button) {
        return;
    }
    const input = document.getElementById(button.dataset.target);
    if (!input) {
        return;
    }

    // Toggle the field first, and never gate it on finding the icon. An earlier version
    // bailed out when its `querySelector('i')` came back null, so when the markup moved
    // from Phosphor <i> to a Material Symbols <span> the whole button stopped working —
    // a cosmetic detail had been made load-bearing.
    const wasVisible = input.type === 'text';
    input.type = wasVisible ? 'password' : 'text';

    /* Both of these describe what the button will do NEXT, not the state it is in, which
       is what a screen reader user needs to hear before deciding to press it. aria-pressed
       carries the state itself, so the two together read correctly either way. */
    button.setAttribute('aria-label', wasVisible ? 'Show password' : 'Hide password');
    button.setAttribute('aria-pressed', String(!wasVisible));

    const symbol = button.querySelector('.material-symbols-outlined');
    if (symbol) {
        // visibility = "press to reveal", visibility_off = "press to hide". Both are in
        // the font subset; visibility_off was missing once and rendered as its own name.
        symbol.textContent = wasVisible ? 'visibility' : 'visibility_off';
    }
});
