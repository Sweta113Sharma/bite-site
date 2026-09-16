package com.bitesite.web;

import com.bitesite.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lets a template ask for an image at the size it is about to draw it.
 *
 * <p>Menu photos are stored at up to 1600px because the item detail page wants them that
 * big. Everywhere else draws them far smaller — a card at ~190px, a cart line or an admin
 * row at a fraction of that — and every one of those was loading the full file and
 * throwing most of it away.
 *
 * <p>Exposed as a bean so Thymeleaf can call it inline (<code>${@imageUrls.card(...)}</code>)
 * rather than every controller pre-computing a map of URLs for every list it renders.
 * The work itself belongs to the storage backend and is delegated: on Cloudinary, which is
 * what production runs, this is a URL the CDN derives and caches, so no file is re-uploaded
 * and nothing needs backfilling. On local disk it returns the path unchanged.
 *
 * <p>The sizes are device pixels, not CSS pixels — a 190px card on a 3x phone needs ~570.
 */
@Component("imageUrls")
@RequiredArgsConstructor
public class ImageUrls {

    /** Menu cards, the deals carousel and the order-again rail: ~190px CSS at 3x. */
    private static final int CARD = 600;

    /** Cart lines and the canteen's admin list: small square thumbnails. */
    private static final int THUMB = 200;

    /** The item page, where the photo is the point of the screen. */
    private static final int DETAIL = 1200;

    private final FileStorageService fileStorageService;

    public String card(String path) {
        return fileStorageService.thumbnailUrl(path, CARD);
    }

    public String thumb(String path) {
        return fileStorageService.thumbnailUrl(path, THUMB);
    }

    public String detail(String path) {
        return fileStorageService.thumbnailUrl(path, DETAIL);
    }
}
