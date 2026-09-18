/* ============================================================
   CROP, THEN COMPRESS, BEFORE UPLOAD

   Logos, category chips and menu photos are all drawn in a fixed shape (squares for the
   first two, 4:3 for dish photos), so a source image of any other shape ends up centre-
   cropped by object-fit on screen, and the person uploading it has no say in which part
   survives. This puts the frame in front of them first: what they see in the frame is
   what gets stored.

   Then it compresses. The crop is drawn at exactly the size the server keeps
   (ImageUploadProcessor.Kind), so a 6MB phone photo leaves the browser as a few tens of
   KB instead of being posted whole and thrown away server-side — which also means a
   photo over the 5MB upload limit now uploads fine once cropped.

   Deliberately hand-written rather than a library. The CSP allows scripts from this origin
   and Razorpay and nothing else (SecurityConfig.CONTENT_SECURITY_POLICY), so a CDN is out,
   and the project has spent effort removing vendored frontend dependencies rather than
   adding them.

   Opt in with data-crop, on either a .photo-dropzone (the input inside it is used) or a
   file input itself. Options, all on that same element:
     data-crop-aspect   "1" (default) or "4:3"
     data-crop-size     long edge of the output in px (default 512)
     data-crop-quality  lossy quality 0–1 (default 0.92)
     data-crop-title    dialog heading
     data-crop-preview  selector of an element to show the result in
     data-crop-submit   submit the input's form as soon as the crop is accepted
   Without data-crop an input keeps its old behaviour exactly.
   ============================================================ */
(function () {
    'use strict';

    const MAX_ZOOM = 5;          // relative to "just covers the frame"
    const WORKING_EDGE = 4096;   // sources larger than this are downscaled once, on open
    const STAGE_MAX_PX = 360;

    /* ── Dialog ──────────────────────────────────────────────────────── */

    let dialog = null;
    let session = null;          // the one open crop, or null

    function buildDialog() {
        const d = document.createElement('dialog');
        d.className = 'crop-dialog';
        d.setAttribute('aria-labelledby', 'crop-dialog-title');
        d.innerHTML = `
            <h2 class="crop-dialog__title" id="crop-dialog-title"></h2>
            <p class="crop-dialog__hint">Drag to move. Pinch, scroll or use the slider to zoom.</p>
            <canvas class="crop-dialog__stage" tabindex="0"
                    aria-label="Crop area. Arrow keys move the image, plus and minus zoom."></canvas>
            <div class="crop-dialog__zoom">
                <button type="button" class="crop-dialog__icon-btn" data-crop-zoom-out aria-label="Zoom out">
                    <span class="material-symbols-outlined" aria-hidden="true">remove</span>
                </button>
                <input type="range" min="0" max="1000" step="1" value="0" aria-label="Zoom"/>
                <button type="button" class="crop-dialog__icon-btn" data-crop-zoom-in aria-label="Zoom in">
                    <span class="material-symbols-outlined" aria-hidden="true">add</span>
                </button>
            </div>
            <div class="crop-dialog__tools">
                <button type="button" class="btn btn-sm btn-outline-secondary" data-crop-rotate>Rotate</button>
                <button type="button" class="btn btn-sm btn-outline-secondary" data-crop-fit
                        title="Show the whole image, leaving empty edges see-through">Fit whole</button>
                <button type="button" class="btn btn-sm btn-outline-secondary" data-crop-reset>Reset</button>
            </div>
            <p class="crop-dialog__status" role="status" aria-live="polite"></p>
            <div class="crop-dialog__footer">
                <figure class="crop-dialog__preview">
                    <canvas class="crop-dialog__preview-canvas" aria-hidden="true"></canvas>
                    <figcaption>On the menu</figcaption>
                </figure>
                <div class="crop-dialog__actions">
                    <button type="button" class="btn btn-outline-secondary" data-crop-cancel>Cancel</button>
                    <button type="button" class="btn btn-primary" data-crop-apply>Use this</button>
                </div>
            </div>`;
        document.body.appendChild(d);

        // Wired once. Every handler reads the current session, so nothing from an earlier
        // crop can fire against a later one's input.
        const stage = d.querySelector('.crop-dialog__stage');
        const slider = d.querySelector('input[type="range"]');
        slider.addEventListener('input', () => session && session.setZoomFromSlider(+slider.value));
        d.querySelector('[data-crop-zoom-in]').addEventListener('click', () => session && session.zoomBy(1.2));
        d.querySelector('[data-crop-zoom-out]').addEventListener('click', () => session && session.zoomBy(1 / 1.2));
        d.querySelector('[data-crop-rotate]').addEventListener('click', () => session && session.rotate());
        d.querySelector('[data-crop-fit]').addEventListener('click', () => session && session.fit());
        d.querySelector('[data-crop-reset]').addEventListener('click', () => session && session.reset());
        d.querySelector('[data-crop-cancel]').addEventListener('click', () => session && session.cancel());
        d.querySelector('[data-crop-apply]').addEventListener('click', () => session && session.apply());
        // Escape closes a modal dialog through 'cancel'; treat it exactly like the button.
        d.addEventListener('cancel', (e) => {
            e.preventDefault();
            if (session && !session.busy) session.cancel();
        });
        stage.addEventListener('pointerdown', (e) => session && session.pointerDown(e));
        stage.addEventListener('pointermove', (e) => session && session.pointerMove(e));
        stage.addEventListener('pointerup', (e) => session && session.pointerUp(e));
        stage.addEventListener('pointercancel', (e) => session && session.pointerUp(e));
        stage.addEventListener('wheel', (e) => session && session.wheel(e), { passive: false });
        stage.addEventListener('keydown', (e) => session && session.key(e));
        window.addEventListener('resize', () => session && session.layout());
        return d;
    }

    /* ── Options ─────────────────────────────────────────────────────── */

    function readOptions(el) {
        const aspectAttr = (el.getAttribute('data-crop-aspect') || '1').split(':');
        const aspect = aspectAttr.length === 2
            ? parseFloat(aspectAttr[0]) / parseFloat(aspectAttr[1])
            : parseFloat(aspectAttr[0]);
        const size = parseInt(el.getAttribute('data-crop-size') || '512', 10);
        const quality = parseFloat(el.getAttribute('data-crop-quality') || '0.92');
        return {
            aspect: aspect > 0 && isFinite(aspect) ? aspect : 1,
            size: size > 0 ? size : 512,
            quality: quality > 0 && quality <= 1 ? quality : 0.92,
            title: el.getAttribute('data-crop-title') || 'Position your image',
            submit: el.hasAttribute('data-crop-submit'),
        };
    }

    /* ── Image helpers ───────────────────────────────────────────────── */

    function toCanvas(w, h) {
        const c = document.createElement('canvas');
        c.width = Math.max(1, Math.round(w));
        c.height = Math.max(1, Math.round(h));
        return c;
    }

    /* A 48MP phone photo is ~190MB decoded as a canvas. Nothing here needs more than
       WORKING_EDGE across (MAX_ZOOM × the largest output), so shrink once, up front, and
       every later redraw and rotate works on the small copy. Browsers apply EXIF
       orientation when decoding into an <img>, so this copy is already upright. */
    function workingCopy(img) {
        const w = img.naturalWidth;
        const h = img.naturalHeight;
        const long = Math.max(w, h);
        if (long <= WORKING_EDGE) return img;
        const k = WORKING_EDGE / long;
        const c = toCanvas(w * k, h * k);
        const cx = c.getContext('2d');
        cx.imageSmoothingQuality = 'high';
        cx.drawImage(img, 0, 0, c.width, c.height);
        return c;
    }

    function dims(src) {
        return src instanceof HTMLImageElement
            ? { w: src.naturalWidth, h: src.naturalHeight }
            : { w: src.width, h: src.height };
    }

    /* One drawImage from a 4000px source to a 256px target skips most source pixels and
       shimmers on fine detail (sesame seeds, text on a sign). Halving in steps until the
       last step is under 2× is the standard cure and costs a few ms. */
    function drawDownscaled(ctx, src, dx, dy, dw, dh) {
        let cur = src;
        let { w, h } = dims(src);
        while (w / 2 >= Math.abs(dw) && h / 2 >= Math.abs(dh)) {
            const c = toCanvas(w / 2, h / 2);
            const cx = c.getContext('2d');
            cx.imageSmoothingQuality = 'high';
            cx.drawImage(cur, 0, 0, c.width, c.height);
            cur = c;
            w = c.width;
            h = c.height;
        }
        ctx.imageSmoothingQuality = 'high';
        ctx.drawImage(cur, dx, dy, dw, dh);
    }

    function hasTransparency(canvas) {
        const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
        for (let i = 3; i < data.length; i += 4) {
            if (data[i] < 255) return true;
        }
        return false;
    }

    function blobOf(canvas, type, quality) {
        return new Promise((resolve) => canvas.toBlob(resolve, type, quality));
    }

    /* Lossy, smallest first. WebP where the browser can encode it (Chrome, Firefox,
       Android's WebView) keeps transparency and is what the server stores anyway. Where it
       cannot, toBlob silently hands back a PNG instead, so the type is checked: an opaque
       crop then goes as JPEG, and only a transparent one falls back to PNG, because JPEG
       would flatten see-through edges to black. The server re-encodes every upload to
       WebP regardless; this only decides what crosses the network. */
    async function encode(canvas, quality) {
        const webp = await blobOf(canvas, 'image/webp', quality);
        if (webp && webp.type === 'image/webp') return webp;
        if (!hasTransparency(canvas)) {
            const jpeg = await blobOf(canvas, 'image/jpeg', quality);
            if (jpeg) return jpeg;
        }
        return blobOf(canvas, 'image/png');
    }

    function extensionFor(type) {
        return type === 'image/webp' ? 'webp' : type === 'image/jpeg' ? 'jpg' : 'png';
    }

    function formatBytes(n) {
        return n >= 1024 * 1024 ? (n / 1024 / 1024).toFixed(1) + 'MB' : Math.max(1, Math.round(n / 1024)) + 'KB';
    }

    /* ── One crop ────────────────────────────────────────────────────── */

    function CropSession(img, file, input, opts, preview) {
        const stage = dialog.querySelector('.crop-dialog__stage');
        const slider = dialog.querySelector('input[type="range"]');
        const status = dialog.querySelector('.crop-dialog__status');
        const applyBtn = dialog.querySelector('[data-crop-apply]');
        const previewCanvas = dialog.querySelector('.crop-dialog__preview-canvas');
        const ctx = stage.getContext('2d');
        const pctx = previewCanvas.getContext('2d');
        const self = this;

        const original = workingCopy(img);
        let source = original;
        let rotation = 0;
        let sw = 0;
        let sh = 0;

        // Stage and frame geometry, in CSS pixels.
        let stageW = 0;
        let stageH = 0;
        let fx = 0;
        let fy = 0;
        let fw = 0;
        let fh = 0;

        // The view: image top-left on the stage, and CSS px per source px.
        let ox = 0;
        let oy = 0;
        let scale = 1;
        let minScale = 1;
        let coverScale = 1;
        let maxScale = 1;

        const pointers = new Map();
        let pinchDist = 0;
        let pinchMidX = 0;
        let pinchMidY = 0;
        let showGrid = false;
        let gridTimer = 0;
        let frameRequested = false;
        const previewSnapshot = preview ? preview.innerHTML : null;

        this.busy = false;

        function measureSource() {
            const d = dims(source);
            sw = d.w;
            sh = d.h;
        }

        /* The stage fills the dialog's width and takes the frame's shape plus a margin, so
           the part of the photo being cut away stays visible, dimmed, around the frame. */
        this.layout = function () {
            const keep = fw > 0 ? viewState() : null;
            const available = Math.min(STAGE_MAX_PX, stage.parentElement.clientWidth - 2 * parseFloat(getComputedStyle(dialog).paddingLeft || '0'));
            const width = Math.max(200, Math.floor(available || STAGE_MAX_PX));
            const margin = Math.round(width * 0.07);
            fw = width - 2 * margin;
            // Not rounded: the output maps the frame at exactly the requested aspect, and
            // a rounded height would shave a pixel or two off one edge of a 4:3 crop.
            fh = fw / opts.aspect;
            stageW = width;
            stageH = Math.ceil(fh + 2 * margin);
            fx = margin;
            fy = margin;

            const dpr = window.devicePixelRatio || 1;
            stage.style.width = stageW + 'px';
            stage.style.height = stageH + 'px';
            stage.width = Math.round(stageW * dpr);
            stage.height = Math.round(stageH * dpr);
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

            // Preview at roughly the size it is drawn in the app: a 58px chip, or a
            // menu card's photo strip for 4:3.
            const pw = opts.aspect === 1 ? 58 : 80;
            const ph = Math.round(pw / opts.aspect);
            previewCanvas.style.width = pw + 'px';
            previewCanvas.style.height = ph + 'px';
            previewCanvas.width = Math.round(pw * dpr);
            previewCanvas.height = Math.round(ph * dpr);

            computeScales();
            if (keep) restoreView(keep); else resetView();
            requestDraw();
        };

        function computeScales() {
            coverScale = Math.max(fw / sw, fh / sh);
            minScale = Math.min(fw / sw, fh / sh);        // "fit whole": nothing cut off
            maxScale = coverScale * MAX_ZOOM;
        }

        // The view as a fraction of the frame, so a resize or rotation to landscape keeps
        // the same crop rather than snapping back to the centre.
        function viewState() {
            return {
                cx: (fx + fw / 2 - ox) / scale,
                cy: (fy + fh / 2 - oy) / scale,
                rel: scale / coverScale,
            };
        }

        function restoreView(v) {
            scale = clampScale(coverScale * v.rel);
            ox = fx + fw / 2 - v.cx * scale;
            oy = fy + fh / 2 - v.cy * scale;
            clampOffset();
            syncSlider();
        }

        function resetView() {
            scale = coverScale;
            ox = fx + (fw - sw * scale) / 2;
            oy = fy + (fh - sh * scale) / 2;
            syncSlider();
        }

        function clampScale(s) {
            return Math.min(maxScale, Math.max(minScale, s));
        }

        /* Larger than the frame on an axis: it must cover the frame on that axis, so no
           empty strip can be dragged into view. Smaller (zoomed out past "fit"): it may
           sit anywhere inside the frame, and the rest is left transparent. */
        function clampOffset() {
            const dw = sw * scale;
            const dh = sh * scale;
            ox = dw >= fw ? Math.min(fx, Math.max(fx + fw - dw, ox)) : Math.max(fx, Math.min(fx + fw - dw, ox));
            oy = dh >= fh ? Math.min(fy, Math.max(fy + fh - dh, oy)) : Math.max(fy, Math.min(fy + fh - dh, oy));
        }

        /* The slider is logarithmic between "fit whole" and MAX_ZOOM, so each step of it
           feels like the same amount of zoom rather than all the action being at one end. */
        function sliderFor(s) {
            if (maxScale <= minScale) return 0;
            return Math.round(1000 * Math.log(s / minScale) / Math.log(maxScale / minScale));
        }

        function syncSlider() {
            slider.value = String(sliderFor(scale));
        }

        function zoomAt(next, px, py) {
            const target = clampScale(next);
            const ix = (px - ox) / scale;
            const iy = (py - oy) / scale;
            scale = target;
            ox = px - ix * scale;
            oy = py - iy * scale;
            clampOffset();
            syncSlider();
            flashGrid();
            requestDraw();
        }

        function frameCentre() {
            return [fx + fw / 2, fy + fh / 2];
        }

        this.setZoomFromSlider = function (v) {
            const s = minScale * Math.pow(maxScale / minScale, v / 1000);
            zoomAt(s, ...frameCentre());
        };

        this.zoomBy = function (factor) {
            zoomAt(scale * factor, ...frameCentre());
        };

        this.fit = function () {
            scale = minScale;
            ox = fx + (fw - sw * scale) / 2;
            oy = fy + (fh - sh * scale) / 2;
            syncSlider();
            requestDraw();
        };

        this.reset = function () {
            if (rotation !== 0) {
                rotation = 0;
                source = original;
                measureSource();
                computeScales();
            }
            resetView();
            requestDraw();
        };

        /* A quarter turn, for the phone photo that arrives on its side. Redrawn into a
           rotated copy so the rest of the maths never has to know about rotation. */
        this.rotate = function () {
            rotation = (rotation + 90) % 360;
            const d = dims(original);
            const turned = rotation % 180 !== 0;
            const c = toCanvas(turned ? d.h : d.w, turned ? d.w : d.h);
            const cx = c.getContext('2d');
            cx.translate(c.width / 2, c.height / 2);
            cx.rotate(rotation * Math.PI / 180);
            cx.drawImage(original, -d.w / 2, -d.h / 2);
            source = rotation === 0 ? original : c;
            measureSource();
            computeScales();
            resetView();
            requestDraw();
        };

        /* ── Input ── */

        function stagePoint(e) {
            const r = stage.getBoundingClientRect();
            return [e.clientX - r.left, e.clientY - r.top];
        }

        function pinchGeometry() {
            const [a, b] = [...pointers.values()];
            return {
                dist: Math.hypot(a[0] - b[0], a[1] - b[1]),
                midX: (a[0] + b[0]) / 2,
                midY: (a[1] + b[1]) / 2,
            };
        }

        this.pointerDown = function (e) {
            // Capture keeps a drag alive when the finger leaves the stage. It throws for a
            // pointer that is already gone, which must not cost the drag itself.
            try { stage.setPointerCapture(e.pointerId); } catch (ignored) { /* drag still works */ }
            pointers.set(e.pointerId, stagePoint(e));
            if (pointers.size === 2) {
                const g = pinchGeometry();
                pinchDist = g.dist;
                pinchMidX = g.midX;
                pinchMidY = g.midY;
            }
            showGrid = true;
            requestDraw();
        };

        this.pointerMove = function (e) {
            if (!pointers.has(e.pointerId)) return;
            const prev = pointers.get(e.pointerId);
            const next = stagePoint(e);
            pointers.set(e.pointerId, next);
            if (pointers.size === 1) {
                ox += next[0] - prev[0];
                oy += next[1] - prev[1];
                clampOffset();
                requestDraw();
            } else if (pointers.size === 2) {
                // Pinch: zoom about the midpoint of the two fingers, and let the midpoint
                // itself carry the image, so a two-finger drag also pans.
                const g = pinchGeometry();
                ox += g.midX - pinchMidX;
                oy += g.midY - pinchMidY;
                if (pinchDist > 0) zoomAt(scale * (g.dist / pinchDist), g.midX, g.midY);
                pinchDist = g.dist;
                pinchMidX = g.midX;
                pinchMidY = g.midY;
                clampOffset();
                requestDraw();
            }
        };

        this.pointerUp = function (e) {
            pointers.delete(e.pointerId);
            if (pointers.size === 2) {
                // A third finger lifted: re-anchor the pinch on the two that remain, or the
                // next move would jump by the distance to the finger that left.
                const g = pinchGeometry();
                pinchDist = g.dist;
                pinchMidX = g.midX;
                pinchMidY = g.midY;
            }
            // Down to one finger: pointerMove pans from that finger's own last position.
            if (pointers.size === 0) {
                showGrid = false;
                requestDraw();
            }
        };

        this.wheel = function (e) {
            e.preventDefault();
            // ctrlKey is how a trackpad pinch arrives; its deltas are much smaller.
            const k = e.ctrlKey ? 0.01 : 0.0015;
            const [px, py] = stagePoint(e);
            zoomAt(scale * Math.exp(-e.deltaY * k), px, py);
        };

        this.key = function (e) {
            const step = e.shiftKey ? 40 : 8;
            const moves = { ArrowLeft: [step, 0], ArrowRight: [-step, 0], ArrowUp: [0, step], ArrowDown: [0, -step] };
            if (moves[e.key]) {
                // Arrow keys move the crop window over the image, which is the opposite
                // direction to moving the image under it.
                ox += moves[e.key][0];
                oy += moves[e.key][1];
                clampOffset();
                flashGrid();
                requestDraw();
            } else if (e.key === '+' || e.key === '=') {
                this.zoomBy(1.1);
            } else if (e.key === '-' || e.key === '_') {
                this.zoomBy(1 / 1.1);
            } else if (e.key === 'Enter') {
                this.apply();
            } else {
                return;
            }
            e.preventDefault();
        };

        function flashGrid() {
            showGrid = true;
            clearTimeout(gridTimer);
            gridTimer = setTimeout(() => {
                if (pointers.size === 0) {
                    showGrid = false;
                    requestDraw();
                }
            }, 600);
        }

        /* ── Drawing ── */

        function requestDraw() {
            if (frameRequested) return;
            frameRequested = true;
            requestAnimationFrame(() => {
                frameRequested = false;
                draw();
            });
        }

        function checker(c, x, y, w, h) {
            const size = 10;
            c.fillStyle = '#fffdf6';
            c.fillRect(x, y, w, h);
            c.fillStyle = '#e6dcc4';
            for (let row = 0; row * size < h; row++) {
                for (let col = (row % 2); col * size < w; col += 2) {
                    c.fillRect(x + col * size, y + row * size, Math.min(size, w - col * size), Math.min(size, h - row * size));
                }
            }
        }

        function draw() {
            ctx.clearRect(0, 0, stageW, stageH);
            // Checks inside the frame only: they are the "this will be see-through" signal,
            // which only means something where the output is.
            checker(ctx, fx, fy, fw, fh);
            ctx.imageSmoothingQuality = 'high';
            ctx.drawImage(source, ox, oy, sw * scale, sh * scale);

            // Dim everything outside the frame, leaving the frame itself untouched.
            ctx.save();
            ctx.beginPath();
            ctx.rect(0, 0, stageW, stageH);
            ctx.rect(fx, fy, fw, fh);
            ctx.fillStyle = 'rgba(20, 16, 13, 0.6)';
            ctx.fill('evenodd');
            ctx.restore();

            if (showGrid) {
                ctx.strokeStyle = 'rgba(255, 253, 246, 0.7)';
                ctx.lineWidth = 1;
                ctx.beginPath();
                for (let i = 1; i < 3; i++) {
                    ctx.moveTo(fx + fw * i / 3, fy);
                    ctx.lineTo(fx + fw * i / 3, fy + fh);
                    ctx.moveTo(fx, fy + fh * i / 3);
                    ctx.lineTo(fx + fw, fy + fh * i / 3);
                }
                ctx.stroke();
            }
            ctx.strokeStyle = '#fffdf6';
            ctx.lineWidth = 2;
            ctx.strokeRect(fx - 1, fy - 1, fw + 2, fh + 2);

            drawPreview();
        }

        // The same crop at the size the app draws it, on the chip's own background, so a
        // transparent edge or a subject too small to read shows up here, not on the menu.
        function drawPreview() {
            const w = previewCanvas.width;
            const h = previewCanvas.height;
            const k = w / fw;
            pctx.setTransform(1, 0, 0, 1, 0, 0);
            pctx.fillStyle = '#f2ead9';
            pctx.fillRect(0, 0, w, h);
            pctx.imageSmoothingQuality = 'high';
            pctx.drawImage(source, (ox - fx) * k, (oy - fy) * k, sw * scale * k, sh * scale * k);
        }

        /* ── Finish ── */

        function outputCanvas() {
            const outW = opts.aspect >= 1 ? opts.size : Math.round(opts.size * opts.aspect);
            const outH = opts.aspect >= 1 ? Math.round(opts.size / opts.aspect) : opts.size;
            const out = toCanvas(outW, outH);
            const k = out.width / fw;
            drawDownscaled(out.getContext('2d'), source, (ox - fx) * k, (oy - fy) * k, sw * scale * k, sh * scale * k);
            return out;
        }

        function finish() {
            clearTimeout(gridTimer);
            URL.revokeObjectURL(img.src);
            session = null;
            if (dialog.open) dialog.close();
        }

        /* Cancel clears the input rather than trying to put the old FileList back. An
           empty file input already means "leave the picture as it is" on every form that
           opts in, so this is the same as never having picked a file. */
        this.cancel = function () {
            input.value = '';
            if (preview && previewSnapshot !== null) preview.innerHTML = previewSnapshot;
            finish();
        };

        this.apply = async function () {
            if (self.busy) return;
            self.busy = true;
            applyBtn.disabled = true;
            try {
                const out = outputCanvas();
                const blob = await encode(out, opts.quality);
                if (!blob) throw new Error('encode failed');

                // Writing the result back into the original input is what keeps the server
                // untouched: the same multipart form posts as before, just smaller.
                const base = (file.name || 'image').replace(/\.[^.]+$/, '');
                const cropped = new File([blob], base + '-cropped.' + extensionFor(blob.type), { type: blob.type });
                const transfer = new DataTransfer();
                transfer.items.add(cropped);
                input.files = transfer.files;

                if (preview) {
                    const shown = document.createElement('img');
                    shown.alt = '';
                    shown.src = out.toDataURL('image/png');
                    preview.replaceChildren(shown);
                }
                const note = input.closest('form') && input.closest('form').querySelector('[data-crop-note]');
                if (note) note.textContent = 'Cropped: ' + formatBytes(file.size) + ' → ' + formatBytes(blob.size);

                finish();
                if (opts.submit && input.form) {
                    const button = input.form.querySelector('button[type="submit"], button:not([type])');
                    if (button) {
                        button.disabled = true;
                        button.textContent = 'Saving…';
                    }
                    // submit(), not requestSubmit(): the Upload button is disabled above and
                    // requestSubmit() would re-run validation against a required input
                    // that DataTransfer has already filled.
                    input.form.submit();
                }
            } catch (err) {
                status.textContent = 'Could not prepare this image. Try another file.';
                applyBtn.disabled = false;
                self.busy = false;
            }
        };

        /* ── Open ── */

        dialog.querySelector('.crop-dialog__title').textContent = opts.title;
        applyBtn.textContent = opts.submit ? 'Save image' : 'Use this';
        applyBtn.disabled = false;
        status.textContent = '';
        measureSource();
        fw = 0;
        if (typeof dialog.showModal === 'function') dialog.showModal();
        // Layout after showModal: the dialog has no width to measure until it is open.
        this.layout();
        // Focused so the arrow keys work at once; no ring, since the person arrived by
        // picking a file, not by tabbing. Tabbing back to it shows the ring as usual.
        stage.focus({ preventScroll: true, focusVisible: false });
    }

    function openCropper(file, input, opts, preview) {
        const url = URL.createObjectURL(file);
        const img = new Image();
        img.onload = function () {
            if (!dialog) dialog = buildDialog();
            if (session) session.cancel();
            session = new CropSession(img, file, input, opts, preview);
        };
        // Something the browser cannot decode (HEIC on most desktops, a renamed file):
        // leave the original in the input and let the server give its usual answer.
        img.onerror = function () { URL.revokeObjectURL(url); };
        img.src = url;
    }

    function bind(owner, input, preview) {
        input.addEventListener('change', function () {
            const file = input.files && input.files[0];
            if (!file || !file.type.startsWith('image/')) return;
            // DataTransfer is what makes writing the result back possible; without it
            // there is no crop to apply, so leave the original file alone.
            if (typeof DataTransfer === 'undefined' || typeof HTMLDialogElement === 'undefined') return;
            // Options are read now rather than at page load, so they can change with the page.
            openCropper(file, input, readOptions(owner), preview);
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('[data-crop]').forEach(function (owner) {
            const input = owner.matches('input[type="file"]') ? owner : owner.querySelector('input[type="file"]');
            if (!input) return;
            const selector = owner.getAttribute('data-crop-preview');
            const preview = selector ? document.querySelector(selector) : owner.querySelector('.photo-dropzone-preview');
            bind(owner, input, preview);
        });
    });
}());
