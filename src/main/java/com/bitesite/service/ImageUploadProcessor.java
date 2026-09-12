package com.bitesite.service;

import com.bitesite.exception.BusinessException;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.luciad.imageio.webp.WebPWriteParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Turns whatever a canteen uploads into the one thing the app stores and serves: a WebP,
 * no larger than it needs to be. Shared by every {@link FileStorageService} implementation
 * so local disk and Cloudinary cannot drift on what they accept or what they keep.
 *
 * <p>Before this, the bytes a manager picked in the file dialog were the bytes every
 * student downloaded — a 254KB PNG for a 374px thumbnail was the measured case, and a
 * phone JPEG is easily 2MB. Re-encoding here means the accepted input can be broad
 * (anything ImageIO can decode: PNG, JPEG, GIF, BMP, WebP) while what reaches disk or
 * Cloudinary is always small and always the same format.
 *
 * <p>Three things that are easy to get wrong, all handled here:
 * <ul>
 *   <li><b>The declared content type is not trusted.</b> The format is sniffed from the
 *       bytes, and a file that does not decode is rejected. A polyglot cannot survive a
 *       decode/re-encode round trip, which is a stronger guarantee than the old whitelist
 *       on a client-supplied header. SVG — an XML format that can embed {@code <script>},
 *       served straight from {@code /uploads/**} — cannot be decoded by ImageIO and so
 *       stays out.
 *   <li><b>Dimensions are checked before the pixels are decoded.</b> A 2MB PNG can decode
 *       to hundreds of megapixels of flat colour; reading the header first means a
 *       decompression bomb costs a few KB of buffer, not the heap.
 *   <li><b>EXIF orientation is applied.</b> ImageIO ignores it and the re-encoded WebP
 *       carries no tag, so a portrait phone photo would otherwise be stored on its side.
 *       Browsers rotate the original for display, which is why this was never visible.
 * </ul>
 */
@Slf4j
final class ImageUploadProcessor {

    /** What an upload is for decides how big it is allowed to stay. */
    enum Kind {
        /** Shown at 28–56px in the navbar, admin lists and the canteen picker; 512
         * leaves headroom for high-DPI screens. Quality is high because logos are flat
         * graphics where compression artefacts show. */
        LOGO("Logo", 512, 0.90f),
        /** Full-width on a phone at most: 1600 covers a 3x-DPR screen on a 500px-wide
         * card, and the item page shows nothing wider. */
        MENU_PHOTO("Photo", 1600, 0.80f);

        final String label;
        final int maxEdge;
        final float quality;

        Kind(String label, int maxEdge, float quality) {
            this.label = label;
            this.maxEdge = maxEdge;
            this.quality = quality;
        }
    }

    /** The bytes to store, already WebP, plus the size they encode. */
    record ProcessedImage(byte[] bytes, int width, int height) {
    }

    static final String CONTENT_TYPE = "image/webp";
    static final String EXTENSION = ".webp";

    /** Matches spring.servlet.multipart.max-file-size, so the two limits give the same
     * answer. Larger inputs cost nothing extra to store now that they are re-encoded; what
     * bounds memory is {@link #MAX_SOURCE_PIXELS}, not this. */
    private static final long MAX_INPUT_BYTES = 5 * 1024 * 1024;
    /** 6000×4000 — a 24MP camera frame. Decoding that already needs ~70MB of heap. */
    private static final long MAX_SOURCE_PIXELS = 24_000_000L;

    private ImageUploadProcessor() {
    }

    static ProcessedImage process(MultipartFile file, Kind kind) {
        if (file.isEmpty()) {
            throw new BusinessException("No file was uploaded.");
        }
        if (file.getSize() > MAX_INPUT_BYTES) {
            throw new BusinessException(kind.label + " must be under 5MB.");
        }
        byte[] source;
        try {
            source = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("Could not read the uploaded " + kind.label.toLowerCase() + " — please try again.");
        }

        BufferedImage decoded = decode(source, kind);
        BufferedImage fitted = scaleToFit(decoded, kind.maxEdge);
        BufferedImage upright = applyOrientation(fitted, readOrientation(source));
        byte[] webp = encode(upright, kind);
        log.debug("{} re-encoded: {} bytes {}x{} -> {} bytes {}x{} webp", kind.label, source.length,
                decoded.getWidth(), decoded.getHeight(), webp.length, upright.getWidth(), upright.getHeight());
        return new ProcessedImage(webp, upright.getWidth(), upright.getHeight());
    }

    /**
     * Sniffs the format, checks the header dimensions, then reads the pixels — subsampled
     * when the source is far larger than it will end up, so a 24MP frame is not
     * materialised in full only to be thrown away.
     */
    private static BufferedImage decode(byte[] source, Kind kind) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BusinessException(kind.label + " must be an image — PNG, JPEG, GIF, BMP, or WebP.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new BusinessException(kind.label + " couldn't be read — please try another image.");
                }
                if ((long) width * height > MAX_SOURCE_PIXELS) {
                    throw new BusinessException(kind.label + " is too large — up to 24 megapixels is fine.");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                // Integer step that keeps the long edge at or above the target, so the
                // real resample in scaleToFit() still has a little to work with.
                int step = Math.max(1, Math.max(width, height) / kind.maxEdge);
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    throw new BusinessException(kind.label + " couldn't be read — please try another image.");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            // ImageIO reports a corrupt or unsupported file (CMYK JPEG, truncated PNG) as
            // an IIOException, an IllegalArgumentException, or occasionally an index error.
            if (e instanceof BusinessException be) {
                throw be;
            }
            log.info("Rejected {} upload that failed to decode: {}", kind.label.toLowerCase(), e.toString());
            throw new BusinessException(kind.label + " couldn't be read — please try another image.");
        }
    }

    /**
     * Draws into a fresh INT_RGB / INT_ARGB image at the target size. Always copying,
     * even when no resize is needed, is deliberate: it normalises whatever colour model
     * the decoder produced (indexed GIF, 16-bit PNG, greyscale JPEG) into the two the
     * WebP writer handles directly.
     */
    private static BufferedImage scaleToFit(BufferedImage image, int maxEdge) {
        int width = image.getWidth();
        int height = image.getHeight();
        int longEdge = Math.max(width, height);
        if (longEdge > maxEdge) {
            double ratio = (double) maxEdge / longEdge;
            width = Math.max(1, (int) Math.round(width * ratio));
            height = Math.max(1, (int) Math.round(height * ratio));
        }
        boolean alpha = image.getColorModel().hasAlpha();
        BufferedImage target = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /** The EXIF orientation tag (1–8), or 1 when there is none or it cannot be read. */
    private static int readOrientation(byte[] source) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(source));
            ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                int orientation = exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                if (orientation >= 1 && orientation <= 8) {
                    return orientation;
                }
            }
        } catch (Exception e) {
            // Missing or malformed metadata is not a reason to refuse the image; it just
            // means there is no rotation to undo.
            log.debug("No usable EXIF orientation: {}", e.toString());
        }
        return 1;
    }

    /**
     * Rotates and/or flips so the stored pixels are upright. Values 5–8 swap width and
     * height. Applied after the resize because a rotation is the same work at any size
     * and the image is smallest here.
     */
    private static BufferedImage applyOrientation(BufferedImage image, int orientation) {
        if (orientation == 1) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.scale(-1, 1); t.translate(-w, 0); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.scale(1, -1); t.translate(0, -h); }
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { return image; }
        }
        boolean swap = orientation >= 5;
        BufferedImage target = new BufferedImage(swap ? h : w, swap ? w : h, image.getType());
        new AffineTransformOp(t, AffineTransformOp.TYPE_BILINEAR).filter(image, target);
        return target;
    }

    private static byte[] encode(BufferedImage image, Kind kind) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType(CONTENT_TYPE);
        if (!writers.hasNext()) {
            // The plugin is on the classpath, so this means its native library failed to
            // load — a platform the bundle does not cover, or an unwritable tmpdir.
            log.error("No WebP ImageIO writer is registered; uploads cannot be processed");
            throw new BusinessException("Image processing is unavailable right now — please try again later.");
        }
        ImageWriter writer = null;
        try {
            // Creating the writer is what first loads the native library, so it belongs
            // inside the catch: an UnsatisfiedLinkError here must become a clean refusal.
            writer = writers.next();
            WebPWriteParam param = new WebPWriteParam(writer.getLocale());
            param.setCompressionType("Lossy");
            param.setCompressionQuality(kind.quality);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream imageOut = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(imageOut);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException | LinkageError e) {
            log.error("WebP encoding failed for {} ({}x{})", kind.label.toLowerCase(), image.getWidth(),
                    image.getHeight(), e);
            throw new BusinessException("Could not process the uploaded " + kind.label.toLowerCase() + " — please try again.");
        } finally {
            if (writer != null) {
                writer.dispose();
            }
        }
    }
}
