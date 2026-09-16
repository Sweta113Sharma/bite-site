package com.bitesite.web;

import com.bitesite.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sizes templates ask for, and that they are actually passed on.
 *
 * <p>Worth pinning because development cannot show it. Local disk storage returns every
 * path unchanged, so a rendered page here looks identical whether this class asks for
 * 600px or forgets to ask at all — the difference only appears in production, on
 * Cloudinary, as a bill for bytes nobody looks at.
 */
class ImageUrlsTest {

    /** Records what it was asked for instead of resizing anything. */
    private static final class RecordingStorage implements FileStorageService {
        final List<String> calls = new ArrayList<>();

        @Override
        public String thumbnailUrl(String path, int edgePx) {
            calls.add(path + "@" + edgePx);
            return path + "?w=" + edgePx;
        }

        @Override
        public String storeLogo(Long tenantId, MultipartFile file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String storeOutletLogo(Long tenantId, Long outletId, MultipartFile file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String storeMenuItemPhoto(Long tenantId, MultipartFile file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String storeCategoryImage(Long tenantId, MultipartFile file) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void eachSiteAsksForTheSizeItDraws() {
        RecordingStorage storage = new RecordingStorage();
        ImageUrls urls = new ImageUrls(storage);

        urls.card("/p.webp");
        urls.thumb("/p.webp");
        urls.detail("/p.webp");

        // A card is ~190px CSS and a phone is up to 3x; a cart line and an admin row are
        // far smaller; the item page is the one screen where the photo is the point.
        assertThat(storage.calls).containsExactly("/p.webp@600", "/p.webp@200", "/p.webp@1200");
    }

    /** Every size must be well under the 1600px the originals are stored at, or none of
     * this saves anything. */
    @Test
    void everySizeIsSmallerThanTheStoredOriginal() {
        RecordingStorage storage = new RecordingStorage();
        ImageUrls urls = new ImageUrls(storage);

        urls.card("/p.webp");
        urls.thumb("/p.webp");
        urls.detail("/p.webp");

        assertThat(storage.calls)
                .allSatisfy(call -> assertThat(Integer.parseInt(call.split("@")[1])).isLessThan(1600));
    }

    /** The returned value is the backend's, passed through untouched. */
    @Test
    void returnsWhatTheStorageBackendSays() {
        ImageUrls urls = new ImageUrls(new RecordingStorage());

        assertThat(urls.card("/p.webp")).isEqualTo("/p.webp?w=600");
    }
}
