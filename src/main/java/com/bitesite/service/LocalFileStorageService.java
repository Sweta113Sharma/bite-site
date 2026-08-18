package com.bitesite.service;

import com.bitesite.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Default storage: the app's own local disk. Fine for a single server instance with
 * persistent storage; not fine for an ephemeral/serverless filesystem or more than one
 * instance — see {@link CloudinaryFileStorageService} for that case. */
@Service
@ConditionalOnProperty(prefix = "app.uploads", name = "storage-type", havingValue = "local", matchIfMissing = true)
@Slf4j
public class LocalFileStorageService implements FileStorageService {

    private final Path logoDir;
    private final Path menuPhotoDir;

    public LocalFileStorageService(@Value("${app.uploads.logo-dir}") String logoDir,
            @Value("${app.uploads.menu-photo-dir}") String menuPhotoDir) {
        // Normalized + absolute once here so the containment check in store() compares
        // like with like — otherwise "./uploads/logos" vs. the normalized resolved target
        // ("uploads/logos") never .equals() even when they're the same directory.
        this.logoDir = Path.of(logoDir).toAbsolutePath().normalize();
        this.menuPhotoDir = Path.of(menuPhotoDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.logoDir);
            Files.createDirectories(this.menuPhotoDir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create upload directories: " + logoDir + ", " + menuPhotoDir, e);
        }
        log.info("Logo storage: local disk at {}", this.logoDir);
        log.info("Menu photo storage: local disk at {}", this.menuPhotoDir);
    }

    @Override
    public String storeLogo(Long tenantId, MultipartFile file) {
        String filename = store(file, "Logo", logoDir, "tenant-" + tenantId);
        return "/uploads/logos/" + filename;
    }

    @Override
    public String storeMenuItemPhoto(Long tenantId, MultipartFile file) {
        String filename = store(file, "Photo", menuPhotoDir, "menu-" + tenantId);
        return "/uploads/menu-photos/" + filename;
    }

    private String store(MultipartFile file, String label, Path dir, String filenamePrefix) {
        String extension = ImageUploadValidation.validateAndGetExtension(file, label);
        String filename = filenamePrefix + "-" + UUID.randomUUID() + extension;
        try {
            Path target = dir.resolve(filename).toAbsolutePath().normalize();
            if (!target.getParent().equals(dir)) {
                throw new BusinessException("Invalid file name.");
            }
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to store {} in {}", label.toLowerCase(), dir, e);
            throw new BusinessException("Could not save the uploaded " + label.toLowerCase() + " — please try again.");
        }
        return filename;
    }
}
