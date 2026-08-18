package com.bitesite.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * Seam for where uploaded files (tenant logos today) actually live. Two implementations
 * exist — {@link LocalFileStorageService} (default) and {@link CloudinaryFileStorageService}
 * (active when {@code app.uploads.storage-type=cloudinary}) — selected at startup via
 * {@code @ConditionalOnProperty}, never both at once.
 */
public interface FileStorageService {

    /**
     * Stores the file and returns a fully resolvable path or URL for displaying it — a
     * root-relative path like {@code /uploads/logos/xyz.png} for local storage, or an
     * absolute CDN URL for cloud storage. Callers (and templates) render this value
     * directly; they never guess at or reconstruct the path themselves.
     */
    String storeLogo(Long tenantId, MultipartFile file);

    /** Same contract as {@link #storeLogo}, for a canteen's menu item photos. */
    String storeMenuItemPhoto(Long tenantId, MultipartFile file);
}
