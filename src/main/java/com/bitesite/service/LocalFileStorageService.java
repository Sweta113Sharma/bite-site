package com.bitesite.service;

import com.bitesite.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class LocalFileStorageService implements FileStorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/svg+xml");
    private static final long MAX_BYTES = 2 * 1024 * 1024;

    private final Path logoDir;

    public LocalFileStorageService(@Value("${app.uploads.logo-dir}") String logoDir) {
        // Normalized + absolute once here so the containment check in storeLogo() compares
        // like with like — otherwise "./uploads/logos" vs. the normalized resolved target
        // ("uploads/logos") never .equals() even when they're the same directory.
        this.logoDir = Path.of(logoDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.logoDir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create logo upload directory: " + logoDir, e);
        }
    }

    @Override
    public String storeLogo(Long tenantId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException("No file was uploaded.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException("Logo must be under 2MB.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException("Logo must be a PNG, JPEG, WebP, or SVG image.");
        }

        String extension = switch (contentType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".svg";
        };
        String filename = "tenant-" + tenantId + "-" + UUID.randomUUID() + extension;

        try {
            Path target = logoDir.resolve(filename).toAbsolutePath().normalize();
            if (!target.getParent().equals(logoDir)) {
                throw new BusinessException("Invalid file name.");
            }
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to store logo for tenant {}", tenantId, e);
            throw new BusinessException("Could not save the uploaded logo — please try again.");
        }
        return filename;
    }
}
