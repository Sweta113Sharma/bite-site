package com.bitesite.service;

import org.springframework.web.multipart.MultipartFile;

/** Seam for where uploaded files (tenant logos today) actually live. Local-disk now;
 * swap for an S3-backed implementation later without touching any caller. */
public interface FileStorageService {

    /** Stores the file and returns the name it was stored under (not a full path/URL). */
    String storeLogo(Long tenantId, MultipartFile file);
}
