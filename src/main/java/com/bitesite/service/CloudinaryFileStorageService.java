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
        return upload(file, "Logo", "bitesite/logos", "tenant-" + tenantId);
    }

    @Override
    public String storeMenuItemPhoto(Long tenantId, MultipartFile file) {
        return upload(file, "Photo", "bitesite/menu-photos", "menu-" + tenantId);
    }

    private String upload(MultipartFile file, String label, String folder, String publicIdPrefix) {
        ImageUploadValidation.validateAndGetExtension(file, label); // validates type/size; Cloudinary assigns its own extension

        Map<String, Object> options = new HashMap<>();
        options.put("folder", folder);
        options.put("public_id", publicIdPrefix + "-" + UUID.randomUUID());
        options.put("overwrite", true);

        try {
            Map<?, ?> result = cloudinary.uploader().upload(file.getBytes(), options);
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
