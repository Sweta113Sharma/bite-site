package com.bitesite.service;

import com.bitesite.exception.BusinessException;
import com.cloudinary.Cloudinary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cloud storage backed by Cloudinary — active when {@code app.uploads.storage-type=cloudinary}.
 * Chosen over raw S3 for this specific use case (a handful of small logo images): a
 * forever-free tier at this scale (not a 12-month trial), an API purpose-built for image
 * uploads, and automatic CDN delivery. Needs {@code CLOUDINARY_CLOUD_NAME},
 * {@code CLOUDINARY_API_KEY}, and {@code CLOUDINARY_API_SECRET} to actually activate —
 * until then the app stays on local-disk storage by default.
 */
@Service
@ConditionalOnProperty(prefix = "app.uploads", name = "storage-type", havingValue = "cloudinary")
@Slf4j
public class CloudinaryFileStorageService implements FileStorageService {

    private final Cloudinary cloudinary;

    public CloudinaryFileStorageService(
            @Value("${app.uploads.cloudinary.cloud-name}") String cloudName,
            @Value("${app.uploads.cloudinary.api-key}") String apiKey,
            @Value("${app.uploads.cloudinary.api-secret}") String apiSecret) {
        if (cloudName.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            throw new IllegalStateException(
                    "app.uploads.storage-type=cloudinary but CLOUDINARY_CLOUD_NAME / CLOUDINARY_API_KEY / "
                            + "CLOUDINARY_API_SECRET aren't all set.");
        }
        Map<String, Object> config = new HashMap<>();
        config.put("cloud_name", cloudName);
        config.put("api_key", apiKey);
        config.put("api_secret", apiSecret);
        config.put("secure", true);
        this.cloudinary = new Cloudinary(config);
        log.info("Logo storage: Cloudinary (cloud: {})", cloudName);
    }

    @Override
    public String storeLogo(Long tenantId, MultipartFile file) {
        return upload(file, ImageUploadProcessor.Kind.LOGO, "bitesite/logos", "tenant-" + tenantId);
    }

    @Override
    public String storeOutletLogo(Long tenantId, Long outletId, MultipartFile file) {
        return upload(file, ImageUploadProcessor.Kind.LOGO, "bitesite/logos", "outlet-" + tenantId + "-" + outletId);
    }

    @Override
    public String storeMenuItemPhoto(Long tenantId, MultipartFile file) {
        return upload(file, ImageUploadProcessor.Kind.MENU_PHOTO, "bitesite/menu-photos", "menu-" + tenantId);
    }

    @Override
    public String storeCategoryImage(Long tenantId, MultipartFile file) {
        // Null tenant means an admin's platform-wide default, which belongs to no college.
        String owner = tenantId == null ? "platform" : String.valueOf(tenantId);
        return upload(file, ImageUploadProcessor.Kind.CATEGORY_IMAGE,
                "bitesite/category-images", "category-" + owner);
    }

    /**
     * Inserts a resize transformation into the delivery URL.
     *
     * <p>Cloudinary derives this on first request and caches it on the CDN, so an existing
     * 1600px upload can be served at 160px without re-uploading anything or storing a
     * second file — which is what makes fixing the oversized category chips a URL change
     * rather than a migration.
     *
     * <p>{@code c_fill} crops to a square (a chip is a circle, so the edges are masked
     * anyway), {@code f_auto} lets the CDN pick AVIF where the browser takes it, and
     * {@code q_auto} picks a quality for the size actually being delivered.
     *
     * <p>Anything that is not a Cloudinary upload URL is returned untouched: seeded rows
     * point at static assets under {@code /img/}, and a transformation spliced into one of
     * those would produce a 404 instead of a picture.
     */
    @Override
    public String thumbnailUrl(String path, int edgePx) {
        return withThumbnailTransformation(path, edgePx);
    }

    /**
     * The rewrite itself, static and package-private so it can be tested without standing
     * up a Cloudinary client. Constructing this service needs three credentials and builds
     * that client eagerly, which a test of pure string work has no business doing — and a
     * test that copies the logic instead would keep passing after the real one changed.
     */
    static String withThumbnailTransformation(String path, int edgePx) {
        if (path == null || !path.contains(UPLOAD_SEGMENT)) {
            return path;
        }
        // c_limit, not c_fill: scale to FIT inside the box and never upscale. c_fill crops
        // to the exact box, which is right for a circular chip and wrong for the 4:3 menu
        // cards — it would centre-crop every dish. The chip still looks right because its
        // CSS already applies object-fit: cover to whatever it is given.
        String transformation = "c_limit,f_auto,q_auto,w_" + edgePx + ",h_" + edgePx + "/";
        return path.replaceFirst(UPLOAD_SEGMENT, UPLOAD_SEGMENT + transformation);
    }

    /** Where a transformation goes in a Cloudinary delivery URL. */
    private static final String UPLOAD_SEGMENT = "/image/upload/";

    private String upload(MultipartFile file, ImageUploadProcessor.Kind kind, String folder, String publicIdPrefix) {
        // Re-encoded here rather than by Cloudinary's own transformations so that what is
        // stored is identical whichever backend is active, and the free tier's storage and
        // bandwidth quotas hold WebP rather than the original.
        ImageUploadProcessor.ProcessedImage image = ImageUploadProcessor.process(file, kind);
        String label = kind.label;

        Map<String, Object> options = new HashMap<>();
        options.put("folder", folder);
        options.put("public_id", publicIdPrefix + "-" + UUID.randomUUID());
        options.put("overwrite", true);

        try {
            Map<?, ?> result = cloudinary.uploader().upload(image.bytes(), options);
            Object secureUrl = result.get("secure_url");
            if (secureUrl == null) {
                throw new BusinessException(label + " upload did not return a URL — please try again.");
            }
            return secureUrl.toString();
        } catch (IOException e) {
            log.error("Failed to upload {} to Cloudinary (folder {})", label.toLowerCase(), folder, e);
            throw new BusinessException("Could not save the uploaded " + label.toLowerCase() + " — please try again.");
        }
    }
}
