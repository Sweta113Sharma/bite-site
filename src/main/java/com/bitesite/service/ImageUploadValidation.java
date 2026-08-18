package com.bitesite.service;

import com.bitesite.exception.BusinessException;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/** Shared upload validation for every {@link FileStorageService} implementation (tenant
 * logos, menu item photos), so the accepted file types/size limit can't quietly drift
 * between local and cloud storage, or between what a logo vs. a menu photo accepts. */
final class ImageUploadValidation {

    // SVG is deliberately excluded: it's an XML format that can embed <script>, and
    // uploaded files are served directly from /uploads/** with no sandboxing — allowing it
    // would be a stored-XSS vector via a malicious "logo" or "menu photo" upload.
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp");
    private static final long MAX_BYTES = 2 * 1024 * 1024;

    private ImageUploadValidation() {
    }

    static String validateAndGetExtension(MultipartFile file, String label) {
        if (file.isEmpty()) {
            throw new BusinessException("No file was uploaded.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(label + " must be under 2MB.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(label + " must be a PNG, JPEG, or WebP image.");
        }
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            default -> ".webp";
        };
    }
}
