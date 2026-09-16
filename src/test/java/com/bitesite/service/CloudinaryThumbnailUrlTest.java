package com.bitesite.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The delivery-URL rewrite that serves an oversized upload at chip size.
 *
 * <p>Tested here specifically because it is the one piece of the category-image work that
 * never runs in development: local disk returns the path untouched, Cloudinary is what
 * production uses, and the difference between the two is a string transformation nobody
 * would notice was wrong until a menu screen quietly went back to downloading 1600px
 * files — or worse, started 404ing because a transformation was spliced into a URL that
 * was never a Cloudinary upload.
 *
 * <p>The rewrite is pure string work with no Cloudinary client involved, so it is exercised
 * directly rather than through the upload path that needs credentials.
 */
class CloudinaryThumbnailUrlTest {

    /**
     * Calls the real implementation. It is static and package-private precisely so this
     * test can reach it without the three credentials the constructor demands — testing a
     * copy of the logic would keep passing after the original changed.
     */
    private static String thumbnailUrl(String path, int edgePx) {
        return CloudinaryFileStorageService.withThumbnailTransformation(path, edgePx);
    }

    @Test
    void insertsTheTransformationAfterUpload() {
        String original = "https://res.cloudinary.com/demo/image/upload/v1699/bitesite/menu-photos/menu-1-abc.webp";

        assertThat(thumbnailUrl(original, 160)).isEqualTo(
                "https://res.cloudinary.com/demo/image/upload/c_limit,f_auto,q_auto,w_160,h_160/"
                        + "v1699/bitesite/menu-photos/menu-1-abc.webp");
    }

    /**
     * Seeded and demo rows point at static assets under /img/. Splicing a Cloudinary
     * transformation into one of those produces a 404 where a picture should be, so
     * anything that is not an upload URL has to come back exactly as it went in.
     */
    @Test
    void leavesNonCloudinaryPathsAlone() {
        assertThat(thumbnailUrl("/img/food/food_chai.png", 160)).isEqualTo("/img/food/food_chai.png");
        assertThat(thumbnailUrl("/uploads/menu-photos/menu-1-abc.webp", 160))
                .isEqualTo("/uploads/menu-photos/menu-1-abc.webp");
    }

    /**
     * Pins the crop mode. c_fill would centre-crop every 4:3 menu card to a square, which
     * is a visual regression nobody would attribute to a caching change months later.
     */
    @Test
    void scalesToFitRatherThanCropping() {
        assertThat(thumbnailUrl("https://res.cloudinary.com/demo/image/upload/v1/x.webp", 600))
                .contains("c_limit")
                .doesNotContain("c_fill");
    }

    /** A category with no image at any rung resolves to null, and that must not throw. */
    @Test
    void toleratesNull() {
        assertThat(thumbnailUrl(null, 160)).isNull();
    }

    /**
     * Only the first occurrence is replaced. A public id that itself contains the segment
     * would otherwise get a second transformation spliced into the middle of the path.
     */
    @Test
    void rewritesOnlyTheFirstOccurrence() {
        String odd = "https://res.cloudinary.com/demo/image/upload/v1/a/image/upload/b.webp";

        assertThat(thumbnailUrl(odd, 160)).isEqualTo(
                "https://res.cloudinary.com/demo/image/upload/c_limit,f_auto,q_auto,w_160,h_160/"
                        + "v1/a/image/upload/b.webp");
    }
}
