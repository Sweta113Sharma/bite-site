package com.bitesite.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * Seam for where uploaded images (tenant logos, canteen logos, menu item photos) actually
 * live. Two implementations exist — {@link LocalFileStorageService} (default) and
 * {@link CloudinaryFileStorageService} (active when {@code app.uploads.storage-type=cloudinary})
 * — selected at startup via {@code @ConditionalOnProperty}, never both at once.
 *
 * <p>Whatever format the caller hands over, what gets stored is a WebP: every
 * implementation runs the upload through {@link ImageUploadProcessor} first, so the
 * returned path always ends in {@code .webp} and the file behind it is already resized.
 */
public interface FileStorageService {

    /**
     * Stores the file and returns a fully resolvable path or URL for displaying it — a
     * root-relative path like {@code /uploads/logos/xyz.webp} for local storage, or an
     * absolute CDN URL for cloud storage. Callers (and templates) render this value
     * directly; they never guess at or reconstruct the path themselves.
     */
    String storeLogo(Long tenantId, MultipartFile file);

    /** Same contract as {@link #storeLogo}, for a canteen's own logo. Lives alongside the
     * tenant logos, distinguished by filename, because it is the same kind of thing at the
     * same sizes — only who owns it differs. */
    String storeOutletLogo(Long tenantId, Long outletId, MultipartFile file);

    /** Same contract as {@link #storeLogo}, for a canteen's menu item photos. */
    String storeMenuItemPhoto(Long tenantId, MultipartFile file);
}
