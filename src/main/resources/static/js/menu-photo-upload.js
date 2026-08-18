document.addEventListener('DOMContentLoaded', function () {
    const dropzone = document.querySelector('.photo-dropzone');
    if (!dropzone) {
        return;
    }
    const input = dropzone.querySelector('input[type="file"]');
    const preview = dropzone.querySelector('.photo-dropzone-preview');

    function showPreview(file) {
        if (!file || !file.type.startsWith('image/')) {
            return;
        }
        const reader = new FileReader();
        reader.onload = function (event) {
            preview.innerHTML = '<img src="' + event.target.result + '" alt="Selected photo"/>';
        };
        reader.readAsDataURL(file);
    }

    input.addEventListener('change', function () {
        if (input.files && input.files[0]) {
            showPreview(input.files[0]);
        }
    });

    ['dragenter', 'dragover'].forEach(function (evt) {
        dropzone.addEventListener(evt, function () {
            dropzone.classList.add('is-dragover');
        });
    });
    ['dragleave', 'drop'].forEach(function (evt) {
        dropzone.addEventListener(evt, function () {
            dropzone.classList.remove('is-dragover');
        });
    });
});
