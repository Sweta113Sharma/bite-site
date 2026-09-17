/* ============================================================
   SQUARE CROP FOR LOGOS

   A college crest and a canteen logo are both drawn as small squares — 28-56px in the
   navbar, 56px on the canteen picker, 88px on the college crest at the top of the picker.
   A wide or off-centre source image therefore ends up letterboxed or awkwardly framed, and
   until now the person uploading it had no say in that: they picked a file and found out
   afterwards.

   This puts a square frame in front of them first. Pan by dragging, zoom with the slider,
   and what they see in the frame is what gets stored.

   Deliberately hand-written rather than a library. The CSP allows scripts from this origin
   and Razorpay and nothing else (SecurityConfig.CONTENT_SECURITY_POLICY), so a CDN is out,
   and the project has spent effort removing vendored frontend dependencies rather than
   adding them. A square cropper is a few dozen lines of canvas.

   Opt in by putting data-crop on a .photo-dropzone. Without it the dropzone keeps its old
   behaviour exactly.
   ============================================================ */
(function () {
    'use strict';

    /* Matches ImageUploadProcessor.Kind.LOGO's maxEdge, so the server has no downscaling
       left to do and what was framed here is what is stored. */
    const OUTPUT_PX = 512;
    /* The frame on screen. Fixed rather than fluid so the pan/zoom maths has one unit. */
    const FRAME_PX = 260;
    const MAX_ZOOM = 4;

    function buildDialog() {
        const dialog = document.createElement('dialog');
        dialog.className = 'crop-dialog';
        dialog.setAttribute('aria-labelledby', 'crop-dialog-title');
        dialog.innerHTML = `
            <h2 class="crop-dialog__title" id="crop-dialog-title">Position your logo</h2>
            <p class="crop-dialog__hint">Drag to move, and use the slider to zoom. The square is what students see.</p>
            <div class="crop-dialog__frame" style="width:${FRAME_PX}px;height:${FRAME_PX}px">
                <canvas class="crop-dialog__canvas" width="${FRAME_PX}" height="${FRAME_PX}"></canvas>
            </div>
            <label class="crop-dialog__zoom">
                <span class="material-symbols-outlined" aria-hidden="true">image</span>
                <input type="range" min="1" max="${MAX_ZOOM}" step="0.01" value="1" aria-label="Zoom"/>
            </label>
            <div class="crop-dialog__actions">
                <button type="button" class="btn btn-outline-secondary" data-crop-cancel>Cancel</button>
                <button type="button" class="btn btn-primary" data-crop-apply>Use this</button>
            </div>`;
        document.body.appendChild(dialog);
        return dialog;
    }

    let dialog = null;

    function openCropper(file, input, preview) {
        const url = URL.createObjectURL(file);
        const img = new Image();

        img.onload = function () {
            if (!dialog) dialog = buildDialog();
            const canvas = dialog.querySelector('.crop-dialog__canvas');
            const zoom = dialog.querySelector('input[type="range"]');
            const ctx = canvas.getContext('2d');

            /* Smallest scale that still covers the frame, so panning can never expose an
               empty edge. Zoom multiplies this, so 1 always means "just covering". */
            const baseScale = Math.max(FRAME_PX / img.naturalWidth, FRAME_PX / img.naturalHeight);
            let scale = baseScale;
            let offsetX = (FRAME_PX - img.naturalWidth * baseScale) / 2;
            let offsetY = (FRAME_PX - img.naturalHeight * baseScale) / 2;
            zoom.value = '1';

            function clamp() {
                const drawnW = img.naturalWidth * scale;
                const drawnH = img.naturalHeight * scale;
                offsetX = Math.min(0, Math.max(FRAME_PX - drawnW, offsetX));
                offsetY = Math.min(0, Math.max(FRAME_PX - drawnH, offsetY));
            }

            function draw() {
                clamp();
                ctx.clearRect(0, 0, FRAME_PX, FRAME_PX);
                ctx.drawImage(img, offsetX, offsetY, img.naturalWidth * scale, img.naturalHeight * scale);
            }

            zoom.oninput = function () {
                const next = baseScale * parseFloat(zoom.value);
                // Zoom about the centre of the frame, not the top-left, or the image walks
                // off toward one corner as you drag the slider.
                const centreX = (FRAME_PX / 2 - offsetX) / scale;
                const centreY = (FRAME_PX / 2 - offsetY) / scale;
                scale = next;
                offsetX = FRAME_PX / 2 - centreX * scale;
                offsetY = FRAME_PX / 2 - centreY * scale;
                draw();
            };

            let dragging = false;
            let lastX = 0;
            let lastY = 0;
            canvas.onpointerdown = function (e) {
                dragging = true;
                lastX = e.clientX;
                lastY = e.clientY;
                canvas.setPointerCapture(e.pointerId);
            };
            canvas.onpointermove = function (e) {
                if (!dragging) return;
                offsetX += e.clientX - lastX;
                offsetY += e.clientY - lastY;
                lastX = e.clientX;
                lastY = e.clientY;
                draw();
            };
            canvas.onpointerup = canvas.onpointercancel = function () { dragging = false; };

            draw();

            const close = () => {
                URL.revokeObjectURL(url);
                if (dialog.open) dialog.close();
            };

            /* Cancel clears the input rather than trying to put the old FileList back.
               An empty file input is already meaningful on both of these forms: "a new
               file replaces, the tick removes, neither leaves the logo as it is." */
            dialog.querySelector('[data-crop-cancel]').onclick = function () {
                input.value = '';
                close();
            };
            dialog.addEventListener('cancel', function () {
                input.value = '';
                URL.revokeObjectURL(url);
            }, { once: true });

            dialog.querySelector('[data-crop-apply]').onclick = function () {
                const out = document.createElement('canvas');
                out.width = OUTPUT_PX;
                out.height = OUTPUT_PX;
                const ratio = OUTPUT_PX / FRAME_PX;
                out.getContext('2d').drawImage(
                    img,
                    offsetX * ratio, offsetY * ratio,
                    img.naturalWidth * scale * ratio, img.naturalHeight * scale * ratio);

                /* PNG, not JPEG: a logo is very often transparent, and JPEG would flatten
                   that to black. The server re-encodes to WebP either way. */
                out.toBlob(function (blob) {
                    if (!blob) { close(); return; }
                    // Writing the crop back into the original input is what keeps the
                    // server untouched — the same multipart form posts as before.
                    const cropped = new File([blob], 'logo.png', { type: 'image/png' });
                    const transfer = new DataTransfer();
                    transfer.items.add(cropped);
                    input.files = transfer.files;
                    if (preview) {
                        preview.innerHTML = '<img alt="Cropped logo"/>';
                        preview.querySelector('img').src = out.toDataURL('image/png');
                    }
                    close();
                }, 'image/png');
            };

            if (typeof dialog.showModal === 'function') dialog.showModal();
        };

        img.onerror = function () { URL.revokeObjectURL(url); };
        img.src = url;
    }

    document.addEventListener('DOMContentLoaded', function () {
        // querySelectorAll, not querySelector: menu-photo-upload.js binds only the first
        // dropzone on a page, which is a limitation worth not inheriting.
        document.querySelectorAll('.photo-dropzone[data-crop]').forEach(function (zone) {
            const input = zone.querySelector('input[type="file"]');
            const preview = zone.querySelector('.photo-dropzone-preview');
            if (!input) return;
            input.addEventListener('change', function () {
                const file = input.files && input.files[0];
                if (!file || !file.type.startsWith('image/')) return;
                // DataTransfer is what makes writing the result back possible; without it
                // there is no crop to apply, so leave the original file alone.
                if (typeof DataTransfer === 'undefined') return;
                openCropper(file, input, preview);
            });
        });
    });
}());
