package com.bitesite.service;

import com.bitesite.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStorageServiceTest {

    @TempDir
    Path root;

    private LocalFileStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorageService(root.resolve("logos").toString(), root.resolve("menu-photos").toString());
    }

    private static MockMultipartFile png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("photo", "dish.png", "image/png", out.toByteArray());
    }

    private void assertStoredWebp(String returnedPath, String urlPrefix, Path dir, String filenamePrefix) throws IOException {
        assertThat(returnedPath).startsWith(urlPrefix + filenamePrefix + "-").endsWith(".webp");
        Path file = dir.resolve(returnedPath.substring(urlPrefix.length()));
        assertThat(file).exists();
        byte[] bytes = Files.readAllBytes(file);
        assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(bytes, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WEBP");
    }

    @Test
    void menuPhotoIsStoredAsWebpUnderTheMenuPhotoDir() throws IOException {
        String path = storage.storeMenuItemPhoto(7L, png(300, 200));

        assertStoredWebp(path, "/uploads/menu-photos/", root.resolve("menu-photos"), "menu-7");
    }

    @Test
    void tenantAndOutletLogosShareTheLogoDirButNotAName() throws IOException {
        String tenant = storage.storeLogo(7L, png(100, 100));
        String outlet = storage.storeOutletLogo(7L, 42L, png(100, 100));

        assertStoredWebp(tenant, "/uploads/logos/", root.resolve("logos"), "tenant-7");
        assertStoredWebp(outlet, "/uploads/logos/", root.resolve("logos"), "outlet-7-42");
        assertThat(tenant).isNotEqualTo(outlet);
    }

    @Test
    void whatIsWrittenIsTheResizedImageNotTheOriginal() throws IOException {
        String path = storage.storeMenuItemPhoto(1L, png(3200, 2400));

        BufferedImage stored = ImageIO.read(root.resolve("menu-photos")
                .resolve(path.substring("/uploads/menu-photos/".length())).toFile());
        assertThat(stored.getWidth()).isEqualTo(1600);
        assertThat(stored.getHeight()).isEqualTo(1200);
    }

    @Test
    void nothingIsWrittenForAnUploadThatIsNotAnImage() {
        MockMultipartFile fake = new MockMultipartFile("photo", "x.png", "image/png",
                "not a picture".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> storage.storeMenuItemPhoto(1L, fake)).isInstanceOf(BusinessException.class);
        assertThat(root.resolve("menu-photos").toFile().list()).isEmpty();
    }
}
