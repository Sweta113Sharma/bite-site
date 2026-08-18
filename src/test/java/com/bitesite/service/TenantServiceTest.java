package com.bitesite.service;

import com.bitesite.exception.ResourceNotFoundException;
import com.bitesite.tenant.Tenant;
import com.bitesite.tenant.TenantDao;
import com.bitesite.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock private TenantDao tenantDao;
    @Mock private FileStorageService fileStorageService;
    @Mock private AuditService auditService;

    private TenantService tenantService;

    private static final Long ACTOR_ID = 100L;

    @BeforeEach
    void setUp() {
        tenantService = new TenantService(tenantDao, fileStorageService, auditService);
    }

    @Test
    void createStartsInPendingStatusNotActive() {
        when(tenantDao.save(any(Tenant.class))).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(5L);
            return t;
        });

        Tenant created = tenantService.create("NID Institute", ACTOR_ID);

        assertThat(created.getStatus()).isEqualTo(TenantStatus.PENDING);
        verify(auditService).record(eq(ACTOR_ID), eq(5L), eq("Tenant"), eq(5L), eq("CREATE"), isNull(), eq(created));
    }

    @Test
    void getThrowsWhenTenantDoesNotExist() {
        when(tenantDao.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.get(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void setStatusAuditsTheTransition() {
        Tenant existing = Tenant.builder().id(5L).name("NID").status(TenantStatus.PENDING).build();
        when(tenantDao.findById(5L)).thenReturn(Optional.of(existing));

        tenantService.setStatus(5L, TenantStatus.ACTIVE, ACTOR_ID);

        verify(tenantDao).updateStatus(5L, TenantStatus.ACTIVE);
        verify(auditService).record(ACTOR_ID, 5L, "Tenant", 5L, "STATUS_ACTIVE", TenantStatus.PENDING, TenantStatus.ACTIVE);
    }

    @Test
    void uploadLogo404sCleanlyForAMissingTenantBeforeTouchingStorage() {
        when(tenantDao.findById(99L)).thenReturn(Optional.empty());
        MockMultipartFile file = new MockMultipartFile("logo", "logo.png", "image/png", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> tenantService.uploadLogo(99L, file, ACTOR_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(fileStorageService);
    }

    @Test
    void uploadLogoStoresTheFileThenPersistsTheReturnedPath() {
        Tenant existing = Tenant.builder().id(5L).name("NID").status(TenantStatus.PENDING).build();
        when(tenantDao.findById(5L)).thenReturn(Optional.of(existing));
        MockMultipartFile file = new MockMultipartFile("logo", "logo.png", "image/png", new byte[]{1, 2, 3});
        when(fileStorageService.storeLogo(5L, file)).thenReturn("/uploads/logos/tenant-5-abc.png");

        tenantService.uploadLogo(5L, file, ACTOR_ID);

        verify(tenantDao).updateLogoPath(5L, "/uploads/logos/tenant-5-abc.png");
        verify(auditService).record(eq(ACTOR_ID), eq(5L), eq("Tenant"), eq(5L), eq("LOGO_UPLOAD"), isNull(),
                eq("/uploads/logos/tenant-5-abc.png"));
    }
}
