package com.bitesite.service;

import com.bitesite.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real encoder — these tests load the native libwebp, so a platform the
 * bundled binaries do not cover fails here rather than on a canteen's first upload.
 */
class ImageUploadProcessorTest {

    // ---- helpers ----

    /** Left half red, right half blue, so a rotation is visible in the pixels. */
    private static BufferedImage halves(int width, int height, boolean alpha) {
        BufferedImage image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width / 2, height);
        g.setColor(Color.BLUE);
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, out)).as("ImageIO can write " + format).isTrue();
        return out.toByteArray();
    }

    private static MockMultipartFile upload(String name, String declaredType, byte[] bytes) {
        return new MockMultipartFile("photo", name, declaredType, bytes);
    }

    private static BufferedImage decodeWebp(byte[] webp) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(webp));
        assertThat(image).as("output decodes as an image").isNotNull();
        return image;
    }

    private static void assertIsWebp(byte[] bytes) {
        assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(bytes, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WEBP");
    }

    private static boolean isRed(int argb) {
        return ((argb >> 16) & 0xFF) > 180 && (argb & 0xFF) < 90;
    }

    private static boolean isBlue(int argb) {
        return (argb & 0xFF) > 180 && ((argb >> 16) & 0xFF) < 90;
    }

    // ---- format conversion ----

    @Test
    void pngComesOutAsWebpAtTheSameSize() throws IOException {
        byte[] png = encode(halves(300, 200, false), "png");

        ImageUploadProcessor.ProcessedImage result = ImageUploadProcessor.process(
                upload("dish.png", "image/png", png), ImageUploadProcessor.Kind.MENU_PHOTO);

        assertIsWebp(result.bytes());
        assertThat(result.width()).isEqualTo(300);
        assertThat(result.height()).isEqualTo(200);
        BufferedImage back = decodeWebp(result.bytes());
        assertThat(isRed(back.getRGB(10, 100))).isTrue();
        assertThat(isBlue(back.getRGB(290, 100))).isTrue();
    }

    @Test
    void jpegGifAndBmpAreAllAccepted() throws IOException {
        for (String format : new String[] {"jpg", "gif", "bmp"}) {
            byte[] bytes = encode(halves(120, 80, false), format);
            ImageUploadProcessor.ProcessedImage result = ImageUploadProcessor.process(
                    upload("dish." + format, "application/octet-stream", bytes), ImageUploadProcessor.Kind.MENU_PHOTO);
            assertIsWebp(result.bytes());
            assertThat(result.width()).as(format).isEqualTo(120);
            assertThat(result.height()).as(format).isEqualTo(80);
        }
    }

    @Test
    void webpInputIsReEncodedRatherThanPassedThrough() throws IOException {
        byte[] first = ImageUploadProcessor.process(
                upload("a.png", "image/png", encode(halves(2000, 1000, false), "png")),
                ImageUploadProcessor.Kind.MENU_PHOTO).bytes();

        // Feeding the output back in must still enforce the size cap for the new kind.
        ImageUploadProcessor.ProcessedImage asLogo = ImageUploadProcessor.process(
                upload("a.webp", "image/webp", first), ImageUploadProcessor.Kind.LOGO);

        assertIsWebp(asLogo.bytes());
        assertThat(asLogo.width()).isEqualTo(512);
        assertThat(asLogo.height()).isEqualTo(256);
    }

    @Test
    void transparencySurvives() throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.GREEN);
        g.fillRect(0, 0, 32, 64); // right half stays fully transparent
        g.dispose();

        byte[] webp = ImageUploadProcessor.process(
                upload("logo.png", "image/png", encode(image, "png")), ImageUploadProcessor.Kind.LOGO).bytes();

        BufferedImage back = decodeWebp(webp);
        assertThat(back.getColorModel().hasAlpha()).isTrue();
        assertThat((back.getRGB(48, 32) >>> 24)).as("alpha of transparent half").isLessThan(16);
        assertThat((back.getRGB(16, 32) >>> 24)).as("alpha of painted half").isGreaterThan(240);
    }

    // ---- sizing ----

    @Test
    void menuPhotosAreCappedAt1600OnTheLongEdge() throws IOException {
        byte[] jpeg = encode(halves(4000, 3000, false), "jpg");

        ImageUploadProcessor.ProcessedImage result = ImageUploadProcessor.process(
                upload("big.jpg", "image/jpeg", jpeg), ImageUploadProcessor.Kind.MENU_PHOTO);

        assertThat(result.width()).isEqualTo(1600);
        assertThat(result.height()).isEqualTo(1200);
        assertThat(result.bytes().length).isLessThan(jpeg.length);
    }

    @Test
    void logosAreCappedAt512AndPortraitKeepsItsRatio() throws IOException {
        byte[] png = encode(halves(600, 1200, false), "png");

        ImageUploadProcessor.ProcessedImage result = ImageUploadProcessor.process(
                upload("logo.png", "image/png", png), ImageUploadProcessor.Kind.LOGO);

        assertThat(result.width()).isEqualTo(256);
        assertThat(result.height()).isEqualTo(512);
    }

    @Test
    void smallImagesAreNotUpscaled() throws IOException {
        ImageUploadProcessor.ProcessedImage result = ImageUploadProcessor.process(
                upload("tiny.png", "image/png", encode(halves(40, 30, false), "png")), ImageUploadProcessor.Kind.LOGO);

        assertThat(result.width()).isEqualTo(40);
        assertThat(result.height()).isEqualTo(30);
    }

    // ---- EXIF orientation ----

    /**
     * Splices a minimal EXIF APP1 segment carrying only the Orientation tag into a JPEG
     * straight after SOI. ImageIO ignores it; the processor must not.
     */
    private static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        assertThat(jpeg[0] & 0xFF).isEqualTo(0xFF);
        assertThat(jpeg[1] & 0xFF).isEqualTo(0xD8);
        // TIFF header (little-endian) + one IFD0 entry + terminator.
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 12 + 4).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 0x2A).putInt(8);
        tiff.putShort((short) 1);                       // one entry
        tiff.putShort((short) 0x0112);                  // Orientation
        tiff.putShort((short) 3);                       // SHORT
        tiff.putInt(1);                                 // count
        tiff.putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);                                 // no IFD1
        byte[] exifHeader = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
        int segmentLength = 2 + exifHeader.length + tiff.capacity();
        ByteBuffer out = ByteBuffer.allocate(jpeg.length + 2 + segmentLength);
        out.put(jpeg, 0, 2);
        out.put((byte) 0xFF).put((byte) 0xE1);
        out.putShort((short) segmentLength);
        out.put(exifHeader);
        out.put(tiff.array());
        out.put(jpeg, 2, jpeg.length - 2);
        return out.array();
    }

    @Test
    void exifOrientation6IsBakedIn() throws IOException {
        // 200 wide, 100 tall, red on the left. Orientation 6 = rotate 90° clockwise, so
        // the stored image must be 100 wide, 200 tall, with red on top.
        byte[] jpeg = withExifOrientation(encode(halves(200, 100, false), "jpg"), 6);

        ImageUploadProcessor.ProcessedImage result = ImageUploadProcessor.process(
                upload("phone.jpg", "image/jpeg", jpeg), ImageUploadProcessor.Kind.MENU_PHOTO);

        assertThat(result.width()).isEqualTo(100);
        assertThat(result.height()).isEqualTo(200);
        BufferedImage back = decodeWebp(result.bytes());
        assertThat(isRed(back.getRGB(50, 10))).as("top is red").isTrue();
        assertThat(isBlue(back.getRGB(50, 190))).as("bottom is blue").isTrue();
    }

    @Test
    void exifOrientation3IsBakedIn() throws IOException {
        byte[] jpeg = withExifOrientation(encode(halves(200, 100, false), "jpg"), 3);

        BufferedImage back = decodeWebp(ImageUploadProcessor.process(
                upload("phone.jpg", "image/jpeg", jpeg), ImageUploadProcessor.Kind.MENU_PHOTO).bytes());

        assertThat(back.getWidth()).isEqualTo(200);
        assertThat(isBlue(back.getRGB(10, 50))).as("180° puts blue on the left").isTrue();
        assertThat(isRed(back.getRGB(190, 50))).isTrue();
    }

    // ---- rejection ----

    @Test
    void declaredContentTypeIsNotTrusted() {
        byte[] notAnImage = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ImageUploadProcessor.process(
                upload("dish.png", "image/png", notAnImage), ImageUploadProcessor.Kind.MENU_PHOTO))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("must be an image");
    }

    @Test
    void svgIsRejected() {
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ImageUploadProcessor.process(
                upload("logo.svg", "image/svg+xml", svg), ImageUploadProcessor.Kind.LOGO))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("must be an image");
    }

    @Test
    void truncatedImageIsRejectedCleanly() throws IOException {
        // PNG, GIF and BMP decoders throw on a cut-off file. (The JPEG decoder is lenient
        // and pads what is missing, so a truncated JPEG stores as a partial picture — an
        // ImageIO property, not something this class chooses.)
        for (String format : new String[] {"png", "gif", "bmp"}) {
            byte[] whole = encode(halves(400, 300, false), format);
            byte[] cut = java.util.Arrays.copyOf(whole, whole.length / 3);

            assertThatThrownBy(() -> ImageUploadProcessor.process(
                    upload("cut." + format, "image/" + format, cut), ImageUploadProcessor.Kind.MENU_PHOTO))
                    .as(format)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("couldn't be read");
        }
    }

    /** A PNG whose header claims 20000×20000 and carries no pixel data at all. */
    private static byte[] pngHeaderClaiming(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteBuffer ihdr = ByteBuffer.allocate(4 + 13);
        ihdr.put("IHDR".getBytes(StandardCharsets.US_ASCII));
        ihdr.putInt(width).putInt(height);
        ihdr.put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        CRC32 crc = new CRC32();
        crc.update(ihdr.array());
        out.writeBytes(ByteBuffer.allocate(4).putInt(13).array());
        out.writeBytes(ihdr.array());
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        return out.toByteArray();
    }

    @Test
    void decompressionBombIsRefusedFromTheHeaderAlone() {
        byte[] bomb = pngHeaderClaiming(20_000, 20_000);

        assertThatThrownBy(() -> ImageUploadProcessor.process(
                upload("bomb.png", "image/png", bomb), ImageUploadProcessor.Kind.MENU_PHOTO))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void emptyAndOversizedUploadsAreRefusedBeforeDecoding() {
        assertThatThrownBy(() -> ImageUploadProcessor.process(
                upload("none.png", "image/png", new byte[0]), ImageUploadProcessor.Kind.MENU_PHOTO))
                .isInstanceOf(BusinessException.class)
                .hasMessage("No file was uploaded.");

        assertThatThrownBy(() -> ImageUploadProcessor.process(
                upload("huge.png", "image/png", new byte[5 * 1024 * 1024 + 1]), ImageUploadProcessor.Kind.LOGO))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Logo must be under 5MB.");
    }
}
