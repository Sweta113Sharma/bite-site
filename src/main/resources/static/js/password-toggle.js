document.addEventListener('click', function (event) {
    const button = event.target.closest('.toggle-password');
    if (!button) {
        return;
    }
    const input = document.getElementById(button.dataset.target);
    const icon = button.querySelector('i');
    if (!input || !icon) {
        return;
    }
    const showing = input.type === 'text';
    input.type = showing ? 'password' : 'text';
    icon.classList.toggle('ph-eye', showing);
    icon.classList.toggle('ph-eye-slash', !showing);
    button.setAttribute('aria-label', showing ? 'Show password' : 'Hide password');
});
