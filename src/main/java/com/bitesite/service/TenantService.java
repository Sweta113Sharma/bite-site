package com.bitesite.service;

import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
import com.bitesite.tenant.TenantStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantDao tenantDao;
    private final FileStorageService fileStorageService;
    private final AuditService auditService;

    public List<Tenant> listAll() {
        return tenantDao.findAll();
    }

    public Tenant get(Long id) {
        return tenantDao.findById(id).orElseThrow(() -> new ResourceNotFoundException("College not found"));
    }

    public Tenant create(String name, Long actorUserId) {
        Tenant tenant = Tenant.builder().name(name).status(TenantStatus.PENDING).build();
        Tenant saved = tenantDao.save(tenant);
        auditService.record(actorUserId, saved.getId(), "Tenant", saved.getId(), "CREATE", null, saved);
        return saved;
    }

    public void setStatus(Long id, TenantStatus status, Long actorUserId) {
        Tenant before = get(id);
        tenantDao.updateStatus(id, status);
        auditService.record(actorUserId, id, "Tenant", id, "STATUS_" + status, before.getStatus(), status);
    }

    public void uploadLogo(Long id, MultipartFile file, Long actorUserId) {
        get(id); // 404s cleanly if the tenant doesn't exist
        String filename = fileStorageService.storeLogo(id, file);
        tenantDao.updateLogoPath(id, filename);
        auditService.record(actorUserId, id, "Tenant", id, "LOGO_UPLOAD", null, filename);
    }
}
